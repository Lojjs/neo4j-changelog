package org.neo4j.changelog;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.neo4j.changelog.config.ConfigReader;
import org.neo4j.changelog.config.GitCommitConfig;
import org.neo4j.changelog.config.GithubConfig;
import org.neo4j.changelog.config.GithubLabelsConfig;
import org.neo4j.changelog.config.ProjectConfig;
import org.neo4j.changelog.git.GitHelper;
import org.neo4j.changelog.github.GitHubHelper;
import org.neo4j.changelog.github.PullRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {

    public static final String IGNORE = "ignore";
    private final ProjectConfig config;

    public Main(@Nonnull ProjectConfig config) throws IOException {
        this.config = config;
    }

    private void generateChangelog() throws IOException, GitAPIException {
        GitHelper gitHelper = new GitHelper(config);

        System.out.println("Checking for tags...");
        List<Ref> versionTags = gitHelper.getVersionTagsForChangelog();
        // Pre-sort the tags
        versionTags.sort(Util.getGitRefSorter(gitHelper));

        System.out.println("Version tags:");
        versionTags.forEach(t -> System.out.println(Util.getTagName(t)));

        ArrayList<String> categories = new ArrayList<>();
        // Defined categories are included
        categories.addAll(config.getCategories());
        // And any possible sub projects
        categories.addAll(config.getSubProjects().stream().map(ProjectConfig::getName).collect(Collectors.toList()));

        System.out.println("Categories to generate log with: " + String.join(", ", categories));
        ChangeLog changeLog = new ChangeLog(versionTags, config.getNextHeader(), categories);

        // Add specified commits to changelog
        List<GitCommitConfig> commits = config.getGitConfig().getCommitsConfig().getCommits();

        if (commits.isEmpty()) {
            System.out.println("Skipping git commits since none were specified");
        } else {
            System.out.println("Adding specified commits to changelog");
            commits.stream()
                   .filter(c -> gitHelper.isAncestorOfToRef(c.getSha()) &&
                           !gitHelper.isAncestorOfFromRef(c.getSha()))
                   .filter(c -> {
                       if (c.getVersionFilter().isEmpty() ||
                               config.getGitConfig().getCommitsConfig().getVersionPrefix().isEmpty()) {
                           return true;
                       }
                       for (String version : c.getVersionFilter()) {
                           if (config.getGitConfig().getCommitsConfig().getVersionPrefix().startsWith(version)) {
                               return true;
                           }
                       }
                       return false;
                   })
                   .map(c -> gitHelper.convertToChange(c, versionTags, config.getNextHeader()))
                   .forEach(changeLog::addToChangeLog);
        }

        // Add project pull requests to changelog
        List<PullRequest> pullRequests = getPullRequests(config.getGithubConfig());

        if (!pullRequests.isEmpty()) {
            System.out.println("Adding relevant PRs to changelog");
            pullRequests.stream()
                        .filter(pr -> gitHelper.isAncestorOfToRef(pr.getCommit()) &&
                                !gitHelper.isAncestorOfFromRef(pr.getCommit()))
                        .map(pr -> GitHubHelper.convertToChange(pr,
                                gitHelper.getFirstVersionOf(pr.getCommit(), versionTags, config.getNextHeader())))
                        .forEach(changeLog::addToChangeLog);
        }

        // Add sub project pull requests to changelog
        addSubprojectChanges(versionTags, changeLog);

        // Write
        changeLog.write(new File(config.getOutputPath()).toPath());
    }

    @Nullable
    private String firstGroup(@Nonnull Pattern pattern, @Nonnull String text) {
        Matcher m = pattern.matcher(text);

        if (!m.matches()) {
            return null;
        }

        return m.group(1);
    }

    private void addSubprojectChanges(List<Ref> orgVersionTags, ChangeLog changeLog) throws IOException, GitAPIException {
        for (ProjectConfig subProjectConfig: config.getSubProjects()) {
            System.out.println("Subproject: " + subProjectConfig.getName());
            GitHelper gitHelper = new GitHelper(subProjectConfig);

            System.out.println("Checking for tags...");
            List<Ref> subTags = gitHelper.getVersionTagsForChangelog();
            // Sort them
            final Pattern tagPattern = subProjectConfig.getGitConfig().getTagPattern();
            subTags.sort(Util.getGitRefSorter(gitHelper, (tag1, tag2) -> {
                // Let mother version break the tie
                for (Ref motherTag: orgVersionTags) {
                    final String motherVersion = Util.getTagName(motherTag);
                    if (motherVersion.equals(firstGroup(tagPattern, Util.getTagName(tag1)))) {
                        return -1;
                    } else if (motherVersion.equals(firstGroup(tagPattern, Util.getTagName(tag2)))) {
                        return 1;
                    }
                }
                // Could not find a matching mother tag, order can not be determined
                return 0;
            }));

            System.out.println("Sub tags:");
            subTags.forEach(t -> System.out.println(Util.getTagName(t)));

            // GIT
            List<GitCommitConfig> commits = subProjectConfig.getGitConfig().getCommitsConfig().getCommits();

            if (commits.isEmpty()) {
                System.out.println("Skipping git commits since none were specified");
            } else {
                System.out.println("Adding specified commits to changelog");
                commits.stream()
                       .filter(c -> gitHelper.isAncestorOfToRef(c.getSha()) &&
                               !gitHelper.isAncestorOfFromRef(c.getSha()))
                       .filter(c -> {
                           if (c.getVersionFilter().isEmpty() ||
                                   subProjectConfig.getGitConfig().getCommitsConfig().getVersionPrefix().isEmpty()) {
                               return true;
                           }
                           for (String version : c.getVersionFilter()) {
                               if (subProjectConfig.getGitConfig().getCommitsConfig().getVersionPrefix().startsWith(version)) {
                                   return true;
                               }
                           }
                           return false;
                       })
                       .map(c -> subChange(c, subProjectConfig, gitHelper, subTags, orgVersionTags))
                       .filter(c -> c != null)
                       .forEach(changeLog::addToChangeLog);
            }

            // GITHUB
            List<PullRequest> pullRequests = getPullRequests(subProjectConfig.getGithubConfig());

            if (!pullRequests.isEmpty()) {
                System.out.println("Adding relevant PRs to changelog");
                pullRequests.stream()
                            .filter(pr -> gitHelper.isAncestorOfToRef(pr.getCommit()) &&
                                    !gitHelper.isAncestorOfFromRef(pr.getCommit()))
                            .map(pr -> subChange(pr, subProjectConfig, gitHelper, subTags, orgVersionTags))
                            .filter(pr -> pr != null)
                            .forEach(changeLog::addToChangeLog);
            }
        }
    }

    @Nullable
    private Change subChange(GitCommitConfig commitConfig, ProjectConfig subProjectConfig, GitHelper gitHelper,
                             List<Ref> subTags, List<Ref> orgVersionTags) {
        final Pattern pattern = subProjectConfig.getGitConfig().getTagPattern();
        final String version = gitHelper.getFirstVersionOf(commitConfig.getSha(), subTags, IGNORE);
        if (version.equals(IGNORE) || !pattern.asPredicate().test(version)) {
            return null;
        }

        // Get mother pattern
        Matcher m = pattern.matcher(version);
        if (!m.matches()) {
            System.out.println("Did not match pattern: " + version);
            return null;
        }
        final String motherVersion = m.group(1);

        if (!orgVersionTags.stream().filter(t -> motherVersion.equals(Util.getTagName(t))).findAny().isPresent()) {
            // Tag does not exist in mother project
            return null;
        }

        return gitHelper.convertToSubChange(commitConfig, subProjectConfig.getName(), motherVersion);
    }

    @Nullable
    private Change subChange(PullRequest pr, ProjectConfig subProjectConfig, GitHelper gitHelper,
                             List<Ref> subTags, List<Ref> orgVersionTags) {
        final Pattern pattern = subProjectConfig.getGitConfig().getTagPattern();
        final String version = gitHelper.getFirstVersionOf(pr.getCommit(), subTags, IGNORE);
        if (version.equals(IGNORE) || !pattern.asPredicate().test(version)) {
            return null;
        }

        // Get mother pattern
        Matcher m = pattern.matcher(version);
        if (!m.matches()) {
            System.out.println("Did not match pattern: " + version);
            return null;
        }
        final String motherVersion = m.group(1);

        if (!orgVersionTags.stream().filter(t -> motherVersion.equals(Util.getTagName(t))).findAny().isPresent()) {
            // Tag does not exist in mother project
            return null;
        }

        return new Change() {
            @Override
            public int getSortingNumber() {
                return pr.getNumber();
            }

            @Nonnull
            @Override
            public List<String> getLabels() {
                return Collections.singletonList(subProjectConfig.getName());
            }

            @Nonnull
            @Override
            public String getVersion() {
                return motherVersion;
            }

            @Override
            public String toString() {
                return pr.getChangeText();
            }
        };
    }

    private static List<PullRequest> getPullRequests(@Nonnull GithubConfig config) {
        String user = config.getUser();
        String repo = config.getRepo();
        String token = config.getToken();
        GithubLabelsConfig labels = config.getLabels();

        if (user.isEmpty() || repo.isEmpty()) {
            System.out.println("Skipping pull requests since no github user/repo defined.");
            return Collections.emptyList();
        }

        System.out.printf("Fetching pull requests from github.com/%s/%s\n", user,
                repo);
        GitHubHelper gitHubHelper = new GitHubHelper(token, user, repo, config.getIncludeAuthor(), labels);

        List<PullRequest> pullRequests = gitHubHelper.getChangeLogPullRequests();

        System.out.printf("%d pull requests fetched.\n", pullRequests.size());

        return pullRequests;
    }


    @Nonnull
    private static Namespace parseArgs(String[] args) throws ArgumentParserException {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("neo4j-changelog")
                                               .defaultHelp(true)
                                               .description("Generate changelog for the given project.");

        parser.addArgument("-c", "--config")
              .help("Path to config file")
              .setDefault("changelog.toml");

        try {
            return parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            throw e;
        }
    }

    public static void main(String[] args) {
        Namespace ns = null;
        try {
            ns = parseArgs(args);
        } catch (ArgumentParserException e) {
            System.exit(1);
        }

        ProjectConfig config = null;
        try {
            config = ConfigReader.parseConfig(ns.getString("config"));
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        try {
            Main main = new Main(config);
            main.generateChangelog();
            System.out.printf("\nDone. Changelog written to %s\n", config.getOutputPath());
        } catch (Exception e) {
            System.err.printf("\nError: An error occurred while building changelog: %s\n", e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
