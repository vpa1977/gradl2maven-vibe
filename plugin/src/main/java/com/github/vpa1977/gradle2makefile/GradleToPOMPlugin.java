package com.github.vpa1977.gradle2makefile;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.PluginCollection;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;

import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gradle plugin that generates a Maven pom.xml for the project.
 *
 * <p>Plugin id: {@code com.github.vpa1977.gradle2pom}
 *
 * <p>For each project it:
 * <ul>
 *   <li>Creates {@code pom.xml} in the project directory.</li>
 *   <li>Uses {@code scala-maven-plugin} to compile Scala sources.</li>
 *   <li>Adds resolved {@code implementation} scope dependencies as Maven
 *       {@code compile} dependencies.</li>
 *   <li>Adds {@code build/generated/sources/<directory>/java} directories that actually
 *       exist on disk via {@code build-helper-maven-plugin}; the plugin is
 *       omitted entirely when no such directories are present.</li>
 * </ul>
 */
public class GradleToPOMPlugin implements Plugin<Project> {

    private record Dependency(String groupId, String artifactId, String version){};

    /**
     * Holds the compiler options gathered from JavaCompile tasks.
     * Classpath, sourcepath, and source-file arguments are excluded.
     *
     * @param release    value of {@code --release} / {@code options.release}, or {@code null}
     * @param sourceCompatibility  value of {@code -source} / {@code options.sourceCompatibility}, or {@code null}
     * @param targetCompatibility  value of {@code -target} / {@code options.targetCompatibility}, or {@code null}
     * @param encoding   value of {@code options.encoding}, or {@code null}
     * @param compilerArgs extra {@code -Xlint} / other flags from {@code options.compilerArgs}
     *                     (classpath/sourcepath entries are stripped)
     */
    private record JavaCompileOptions(
            String release,
            String sourceCompatibility,
            String targetCompatibility,
            String encoding,
            List<String> compilerArgs) {};

    private HashMap<String, Dependency> projectDepencyCache = new HashMap<>();

    /** Build-helper-maven-plugin version used for add-source executions. */
    private static final String BUILD_HELPER_VERSION = "3.5.0";

    /** scala-maven-plugin version. */
    private static final String SCALA_MAVEN_PLUGIN_VERSION = "4.9.2";

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            project.getLogger().error("Please apply plugin to the root project {} not {}",
                    project.getRootProject().getName(), project.getName());
        }

        project.getGradle().buildFinished(result -> {
            populateDependencyCache(project.getAllprojects());
            project.getAllprojects().forEach( entry -> {
                        try {
                            generatePom(entry);
                        } catch (IOException e) {
                            entry.getLogger().error("gradle2pom: failed to generate pom.xml for project '{}': {}",
                                    entry.getName(), e.getMessage(), e);
                        }
                    }
            );
        });

    }

    /**
     * Pre-populates {@link #projectDepencyCache} for every project in the build.
     *
     * <p>The cache key is {@code project.getGroup() + ":" + project.getName()}.
     * The value is the project's Maven publication coordinates obtained via
     * {@link #getMavenCoordinates(Project)}. When a project does not apply the
     * {@code maven-publish} plugin the coordinates fall back to the project's
     * own {@code group}, {@code name}, and {@code version}.
     *
     * <p>Must be called before any {@link #generatePom(Project)} invocation so
     * that {@link #resolveImplementationDeps(Project)} can look up sibling
     * project coordinates without calling {@link #getMavenCoordinates(Project)}
     * repeatedly.
     *
     * @param projects the full set of projects in the build
     *                 (typically {@code project.getAllprojects()})
     */
    private void populateDependencyCache(Iterable<Project> projects) {
        projectDepencyCache.clear();
        for (Project p : projects) {
            String key = p.getGroup() + ":" + p.getName();
            Dependency coords = getMavenCoordinates(p);
            if (coords == null) {
                // Fallback: use the project's own coordinates.
                coords = new Dependency(
                        p.getGroup().toString(),
                        p.getName(),
                        p.getVersion().toString());
            }
            projectDepencyCache.put(key, coords);
        }
    }

    // -------------------------------------------------------------------------
    // POM generation
    // -------------------------------------------------------------------------

    private void generatePom(Project project) throws IOException {

        File pomFile = new File(project.getProjectDir(), "pom.xml");

        String groupId    = stringOf(project.getGroup(), "com.example");
        String artifactId = project.getName();
        String version    = stringOf(project.getVersion(), "1.0-SNAPSHOT");

        Dependency mavenArtifact = getMavenCoordinates(project);
        if (mavenArtifact != null) {
            groupId = mavenArtifact.groupId();
            artifactId = mavenArtifact.artifactId();
            version = mavenArtifact.version();
        }

        var compileDeps = resolveImplementationDeps(project);

        // Annotation-processor deps must also be on the compile classpath so that
        // generated sources (already on disk) can reference their API types.
       // mergeAnnotationProcessorDeps(project, compileDeps);
        List<String> generatedSourceDirs   = findGeneratedSourceDirs(project);
        boolean hasScala                   = projectHasScalaSources(project);
        JavaCompileOptions compilerOptions = collectCompilerOptions(project);

        // For the root project, collect submodule paths for the <modules> section.
        // For non-root projects, collect sibling project() dependencies.
        List<String> submodules = new ArrayList<>();
        List<Dependency> projectDeps = new ArrayList<>();
        if (project == project.getRootProject()) {
            for (Project child : project.getAllprojects()) {
                if (child == project) continue;
                String rel = relativePath(project, child.getProjectDir());
                if ("buildSrc".equals(rel)) continue; // special project with build script
                submodules.add(rel);
            }
        } else {
            projectDeps = resolveProjectDeps(project);
        }

        try (FileWriter w = new FileWriter(pomFile)) {
            w.write(pomXml(groupId, artifactId, version, compileDeps, projectDeps,
                    generatedSourceDirs, hasScala, submodules, compilerOptions));
        }

        project.getLogger().lifecycle("gradle2pom: pom.xml written to {}",
                pomFile.getAbsolutePath());
    }

    /**
     * Get project's maven publication coordinates. The project may produce multiple artifacts,
     * this case is not supported.
     * @param project
     * @return
     */
    private Dependency getMavenCoordinates(Project project) {
        PluginCollection<MavenPublishPlugin> plugins = project.getPlugins().withType(MavenPublishPlugin.class);
        if (plugins.isEmpty()) {
            return null;
        }
        PublishingExtension publishing = project.getExtensions()
                .getByType(PublishingExtension.class);
        var collection = publishing.getPublications().withType(MavenPublication.class);
        var dependencies = new HashSet<>(collection.stream()
                .map(publication -> new Dependency(publication.getGroupId(), publication.getArtifactId(), publication.getVersion()))
                .toList());

        if (dependencies.size() > 1) {
            project.getLogger().error("Multiple publications found in project "+ project.getName());
            dependencies.stream().forEach( x -> {
                    project.getLogger().error(x.groupId() + ":"+x.artifactId() + ":" + x.version());
            });
            throw new IllegalArgumentException("Project produces multiple publications, not supported yet");
        }
        var dep = dependencies.stream().findAny();
        return dep.orElseGet( () -> null);
    }

    // -------------------------------------------------------------------------
    // Dependency resolution
    // -------------------------------------------------------------------------

    /**
     * Returns all resolved artifacts needed on the Maven {@code compile} classpath.
     *
     * <p>Tries configurations in order:
     * <ol>
     *   <li>{@code compileClasspath} — the canonical resolvable configuration that
     *       includes {@code implementation}, {@code compileOnly}, and any types
     *       exposed via the annotation-processor path. This is the right source of
     *       truth for Maven {@code compile} scope.
     *   <li>{@code runtimeClasspath} — fallback for projects that do not apply the
     *       Java plugin in the standard way.
     * </ol>
     */
    private List<Dependency> resolveImplementationDeps(Project project) {

        Set<Dependency> deps = new LinkedHashSet<>();

        for (String name : new String[]{"compileClasspath"}) {
            Configuration cfg = project.getConfigurations().findByName(name);
            if (cfg == null) {
                continue;
            }

            if (!cfg.isCanBeResolved()) {
                for (org.gradle.api.artifacts.Dependency id : cfg.getAllDependencies()) {
                    String moduleKey = id.getGroup() + ":" + id.getName();
                    deps.add(projectDepencyCache.getOrDefault
                            (moduleKey,
                                    new Dependency(id.getGroup(), id.getName(), id.getVersion())));
                }
            } else {
                try {
                    List<ResolvedArtifact> artifacts = new ArrayList<>();
                    Set<ResolvedDependency> firstLevel =
                            cfg.getResolvedConfiguration().getFirstLevelModuleDependencies();
                    collectArtifacts(firstLevel, new LinkedHashSet<>(), artifacts);
                    for (ResolvedArtifact x : artifacts) {
                        var id = x.getModuleVersion().getId();
                        String moduleKey = id.getGroup() + ":" + id.getName();
                        deps.add(projectDepencyCache.getOrDefault
                                (moduleKey,
                                        new Dependency(id.getGroup(), id.getName(), id.getVersion())));
                    }
                } catch (Exception e) {
                    project.getLogger().debug(
                            "gradle2pom: could not resolve configuration '{}': {}", name, e.getMessage());
                }
            }
        }
        return new ArrayList<>(deps);
    }

    /**
     * Resolves the {@code annotationProcessor} configuration and appends any
     * artifacts not already present in {@code target} (matched by group:name:version).
     * Annotation processors often provide API types that generated sources reference,
     * so they must appear on the Maven compile classpath.
     */
    /*
    private void mergeAnnotationProcessorDeps(Project project, List<ResolvedArtifact> target) {
        Configuration cfg = project.getConfigurations().findByName("annotationProcessor");
        if (cfg == null || !cfg.isCanBeResolved()) {
            return;
        }
        // Build a set of already-known coordinates to avoid duplicates.
        Set<String> seen = new LinkedHashSet<>();
        for (ResolvedArtifact art : target) {
            seen.add(art.getModuleVersion().getId().toString());
        }
        try {
            Set<ResolvedDependency> firstLevel =
                    cfg.getResolvedConfiguration().getFirstLevelModuleDependencies();
            List<ResolvedArtifact> apArtifacts = new ArrayList<>();
            collectArtifacts(firstLevel, seen, apArtifacts);
            target.addAll(apArtifacts);
        } catch (Exception e) {
            project.getLogger().debug(
                    "gradle2pom: could not resolve 'annotationProcessor' configuration: {}",
                    e.getMessage());
        }
    }
    */

    /**
     * Returns all sibling project dependencies that this (non-root) project declares
     * via {@code project(":name")} in any of its dependency configurations.
     *
     * <p>These are emitted as Maven {@code compile}-scope dependencies in the POM so
     * that Maven can resolve them from the local reactor during a multi-module build.
     *
     * <p>Uses {@link ProjectDependency}'s own {@code getGroup()}/{@code getName()}/
     * {@code getVersion()} rather than the removed {@code getDependencyProject()} API.
     */
    private List<Dependency> resolveProjectDeps(Project project) {
        ArrayList<Dependency> seen = new ArrayList<>();
        for (var p : project.getSubprojects()) {
            Dependency dep =getMavenCoordinates(p);
            if (dep != null)
                seen.add(dep);
        }
        return seen;
    }

    /** Recursively collects leaf artifacts, avoiding duplicates. */
    private void collectArtifacts(Set<ResolvedDependency> deps,
                                   Set<String> seen,
                                   List<ResolvedArtifact> out) {
        for (ResolvedDependency dep : deps) {
            String id = dep.getModule().getId().toString();
            if (!seen.add(id)) {
                continue;
            }
            out.addAll(dep.getModuleArtifacts());
            collectArtifacts(dep.getChildren(), seen, out);
        }
    }

    // -------------------------------------------------------------------------
    // Compiler options
    // -------------------------------------------------------------------------

    /**
     * Iterates over all {@link JavaCompile} tasks in the project and collects
     * their compiler options. Classpath, sourcepath, and file-list arguments
     * are intentionally excluded because Maven manages those separately.
     *
     * <p>When the project has no {@code JavaCompile} tasks the method returns
     * an empty {@link JavaCompileOptions} record (all fields {@code null} /
     * empty list).
     */
    private JavaCompileOptions collectCompilerOptions(Project project) {
        String release              = null;
        String sourceCompatibility  = null;
        String targetCompatibility  = null;
        String encoding             = null;
        List<String> compilerArgs   = new ArrayList<>();

        // Prefixes of compiler arguments that refer to classpath / sourcepath / file lists.
        // These are Maven's responsibility and must not be duplicated in the POM.
        Set<String> excludedPrefixes = Set.of(
                "-classpath", "-cp", "--class-path",
                "-sourcepath", "--source-path",
                "-Werror"
        );

        for (JavaCompile task : project.getTasks().withType(JavaCompile.class)) {
            var opts = task.getOptions();

            // --release takes precedence over -source/-target
            if (release == null && opts.getRelease().isPresent()) {
                release = opts.getRelease().get().toString();
            }

            // Source / target compatibility (set directly on the task)
            if (sourceCompatibility == null) {
                try {
                    String sc = task.getSourceCompatibility();
                    if (sc != null && !sc.isEmpty()) {
                        sourceCompatibility = sc;
                    }
                } catch (Exception ignored) {}
            }
            if (targetCompatibility == null) {
                try {
                    String tc = task.getTargetCompatibility();
                    if (tc != null && !tc.isEmpty()) {
                        targetCompatibility = tc;
                    }
                } catch (Exception ignored) {}
            }

            // Encoding
            if (encoding == null && opts.getEncoding() != null && !opts.getEncoding().isEmpty()) {
                encoding = opts.getEncoding();
            }

            // Extra compiler args — strip classpath/sourcepath/file entries
            List<String> raw = opts.getCompilerArgs();
            if (raw != null) {
                for (int i = 0; i < raw.size(); i++) {
                    String arg = raw.get(i);
                    boolean excluded = false;
                    for (String prefix : excludedPrefixes) {
                        if (arg.startsWith(prefix)) {
                            excluded = true;
                            // If this is a two-token option (flag + value), skip value too.
                            if (!arg.contains("=") && !arg.equals(prefix)) {
                                // value is part of the same token — already handled
                            } else if (arg.equals(prefix) && i + 1 < raw.size()) {
                                i++; // skip the next token (the value)
                            }
                            break;
                        }
                    }
                    if (!excluded && !compilerArgs.contains(arg)) {
                        compilerArgs.add(arg);
                    }
                }
            }
        }

        return new JavaCompileOptions(release, sourceCompatibility, targetCompatibility,
                encoding, compilerArgs);
    }

    // -------------------------------------------------------------------------
    // Generated-source directories
    // -------------------------------------------------------------------------

    /**
     * Recursively walks {@code build/generated} under the project directory and
     * returns the relative paths of every directory whose path ends with
     * {@code main/java} (i.e. matches the standard Gradle generated-sources
     * layout).  Returns an empty list when none are found, in which case the
     * build-helper-maven-plugin is omitted from the generated POM entirely.
     */
    private List<String> findGeneratedSourceDirs(Project project) {
        List<String> dirs = new ArrayList<>();

        File buildGenerated = new File(project.getProjectDir(), "build/generated");
        if (!buildGenerated.isDirectory()) {
            return dirs;
        }

        collectMainJavaDirs(buildGenerated, "java" + File.separator + "main", project, dirs);
        collectMainJavaDirs(buildGenerated, "main" + File.separator + "java", project, dirs);

        return dirs;
    }

    /**
     * Recursively descends into {@code dir}, adding any subdirectory whose
     * absolute path ends with {@code …/main/java} to {@code result}.
     */
    private void collectMainJavaDirs(File dir, String suffix, Project project, List<String> result) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            String abs = child.getAbsolutePath();
            if (abs.endsWith(File.separator + suffix) || abs.equals(suffix)) {
                result.add(relativePath(project, child));
            } else {
                collectMainJavaDirs(child, suffix, project, result);
            }
        }
    }

    private boolean projectHasScalaSources(Project project) {
        File scalaMain = new File(project.getProjectDir(), "src/main/scala");
        return scalaMain.isDirectory();
    }

    // -------------------------------------------------------------------------
    // XML rendering
    // -------------------------------------------------------------------------

    private String pomXml(String groupId,
                           String artifactId,
                           String version,
                           List<Dependency> deps,
                           List<Dependency> projectDeps,
                           List<String> generatedSourceDirs,
                           boolean hasScala,
                           List<String> submodules,
                           JavaCompileOptions compilerOptions) {
        StringBuilder sb = new StringBuilder();

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ");
        sb.append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n\n");

        sb.append("  <groupId>").append(escape(groupId)).append("</groupId>\n");
        sb.append("  <artifactId>").append(escape(artifactId)).append("</artifactId>\n");
        sb.append("  <version>").append(escape(version)).append("</version>\n");

        // Root aggregator POM must declare pom packaging when it has submodules.
        if (!submodules.isEmpty()) {
            sb.append("  <packaging>pom</packaging>\n");
        }
        sb.append("\n");

        sb.append("<properties>\n");
        sb.append(" <maven.compiler.release>21</maven.compiler.release>\n");
        sb.append("</properties>\n");

        // ---- modules (root project only) ------------------------------------
        if (!submodules.isEmpty()) {
            sb.append("  <modules>\n");
            for (String module : submodules) {
                sb.append("    <module>").append(escape(module)).append("</module>\n");
            }
            sb.append("  </modules>\n\n");
        }

        // ---- dependencies ---------------------------------------------------
        if (!deps.isEmpty() || !projectDeps.isEmpty()) {
            sb.append("  <dependencies>\n");
            // Sibling project dependencies (reactor artifacts) come first.
            for (Dependency pdep : projectDeps) {
                String depGroup    = stringOf(pdep.groupId(), groupId);
                String depArtifact = pdep.artifactId();
                String depVersion  = stringOf(pdep.version(), version);
                sb.append("    <dependency>\n");
                sb.append("      <groupId>").append(escape(depGroup)).append("</groupId>\n");
                sb.append("      <artifactId>").append(escape(depArtifact)).append("</artifactId>\n");
                sb.append("      <version>").append(escape(depVersion)).append("</version>\n");
                sb.append("      <scope>compile</scope>\n");
                sb.append("    </dependency>\n");
            }
            for (var art : deps) {
                String depGroup    = art.groupId();
                String depArtifact = art.artifactId();
                String depVersion  = art.version();
                sb.append("    <dependency>\n");
                sb.append("      <groupId>").append(escape(depGroup)).append("</groupId>\n");
                sb.append("      <artifactId>").append(escape(depArtifact)).append("</artifactId>\n");
                sb.append("      <version>").append(escape(depVersion)).append("</version>\n");
                sb.append("      <scope>compile</scope>\n");
                sb.append("    </dependency>\n");
            }
            sb.append("  </dependencies>\n\n");
        }

        // ---- build / plugins ------------------------------------------------
        sb.append("  <build>\n");
        sb.append("    <plugins>\n");

        // maven-compiler-plugin: carry over JavaCompile task options
        String compilerPluginXml = mavenCompilerPlugin(compilerOptions);
        if (compilerPluginXml != null) {
            sb.append(compilerPluginXml);
        }

        // build-helper-maven-plugin: add generated source directories (only when present)
        if (!generatedSourceDirs.isEmpty()) {
            sb.append(buildHelperPlugin(generatedSourceDirs));
        }

        // scala-maven-plugin (only when Scala sources are present)
        if (hasScala) {
            sb.append(scalaMavenPlugin());
        }

        sb.append("    </plugins>\n");
        sb.append("  </build>\n");

        sb.append("</project>\n");
        return sb.toString();
    }

    /**
     * Renders a {@code maven-compiler-plugin} configuration block from the
     * collected {@link JavaCompileOptions}.
     *
     * <p>Returns {@code null} when there is nothing meaningful to emit (all
     * fields are null/empty) so the caller can omit the plugin entirely.
     */
    private String mavenCompilerPlugin(JavaCompileOptions opts) {
        if (opts == null) {
            return null;
        }
        boolean hasRelease = opts.release() != null;
        boolean hasSource  = opts.sourceCompatibility() != null;
        boolean hasTarget  = opts.targetCompatibility() != null;
        boolean hasEncoding = opts.encoding() != null;
        boolean hasArgs    = !opts.compilerArgs().isEmpty();

        if (!hasRelease && !hasSource && !hasTarget && !hasEncoding && !hasArgs) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("        <artifactId>maven-compiler-plugin</artifactId>\n");
        sb.append("        <configuration>\n");

        if (hasRelease) {
            sb.append("          <release>").append(escape(opts.release())).append("</release>\n");
        }
        if (hasSource) {
            sb.append("          <source>").append(escape(opts.sourceCompatibility())).append("</source>\n");
        }
        if (hasTarget) {
            sb.append("          <target>").append(escape(opts.targetCompatibility())).append("</target>\n");
        }
        if (hasEncoding) {
            sb.append("          <encoding>").append(escape(opts.encoding())).append("</encoding>\n");
        }
        if (hasArgs) {
            sb.append("          <compilerArgs>\n");
            for (String arg : opts.compilerArgs()) {
                sb.append("            <arg>").append(escape(arg)).append("</arg>\n");
            }
            sb.append("          </compilerArgs>\n");
        }

        sb.append("        </configuration>\n");
        sb.append("      </plugin>\n");
        return sb.toString();
    }

    private String buildHelperPlugin(List<String> sourceDirs) {
        StringBuilder sb = new StringBuilder();
        sb.append("      <plugin>\n");
        sb.append("        <groupId>org.codehaus.mojo</groupId>\n");
        sb.append("        <artifactId>build-helper-maven-plugin</artifactId>\n");
        sb.append("        <version>").append(BUILD_HELPER_VERSION).append("</version>\n");
        sb.append("        <executions>\n");
        sb.append("          <execution>\n");
        sb.append("            <phase>generate-sources</phase>\n");
        sb.append("            <goals><goal>add-source</goal></goals>\n");
        sb.append("            <configuration>\n");
        sb.append("              <sources>\n");
        for (String dir : sourceDirs) {
            sb.append("                <source>").append(escape(dir)).append("</source>\n");
        }
        sb.append("              </sources>\n");
        sb.append("            </configuration>\n");
        sb.append("          </execution>\n");
        sb.append("        </executions>\n");
        sb.append("      </plugin>\n");
        return sb.toString();
    }

    private String scalaMavenPlugin() {
        return "      <plugin>\n" +
               "        <groupId>net.alchim31.maven</groupId>\n" +
               "        <artifactId>scala-maven-plugin</artifactId>\n" +
               "        <version>" + SCALA_MAVEN_PLUGIN_VERSION + "</version>\n" +
               "        <executions>\n" +
               "          <execution>\n" +
               "            <phase>process-resources</phase>\n" +
               "            <goals>\n" +
               "              <goal>compile</goal>\n" +
               "              <goal>testCompile</goal>\n" +
               "            </goals>\n" +
               "          </execution>\n" +
               "        </executions>\n" +
               "      </plugin>\n";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String relativePath(Project project, File file) {
        String base   = project.getProjectDir().getAbsolutePath();
        String target = file.getAbsolutePath();
        if (target.startsWith(base + File.separator)) {
            return target.substring(base.length() + 1);
        }
        return target;
    }

    private static String stringOf(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.toString();
        return s.isEmpty() ? fallback : s;
    }

    /** Minimal XML character escaping for element text content. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
