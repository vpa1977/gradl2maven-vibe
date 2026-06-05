package com.github.vpa1977.gradle2makefile;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.initialization.Settings;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
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
            project.getAllprojects().forEach( entry -> entry.afterEvaluate(
                    p -> {
                        try {
                            generatePom(p);
                        } catch (IOException e) {
                            p.getLogger().error("gradle2pom: failed to generate pom.xml for project '{}': {}",
                                    p.getName(), e.getMessage(), e);
                        }
                    }
            ));
        });

    }

    // -------------------------------------------------------------------------
    // POM generation
    // -------------------------------------------------------------------------

    private void generatePom(Project project) throws IOException {
        File pomFile = new File(project.getProjectDir(), "pom.xml");

        String groupId    = stringOf(project.getGroup(), "com.example");
        String artifactId = project.getName();
        String version    = stringOf(project.getVersion(), "1.0-SNAPSHOT");

        var compileDeps = resolveImplementationDeps(project);
        // Annotation-processor deps must also be on the compile classpath so that
        // generated sources (already on disk) can reference their API types.
       // mergeAnnotationProcessorDeps(project, compileDeps);
        List<String> generatedSourceDirs   = findGeneratedSourceDirs(project);
        boolean hasScala                   = projectHasScalaSources(project);

        // For the root project, collect submodule paths for the <modules> section.
        // For non-root projects, collect sibling project() dependencies.
        List<String> submodules = new ArrayList<>();
        List<Dependency> projectDeps = new ArrayList<>();
        if (project == project.getRootProject()) {
            for (Project child : project.getAllprojects()) {
                if (child == project) continue;
                String rel = relativePath(project, child.getProjectDir());
                submodules.add(rel);
            }
        } else {
            projectDeps = new ArrayList<>();//resolveProjectDeps(project);
        }

        try (FileWriter w = new FileWriter(pomFile)) {
            w.write(pomXml(groupId, artifactId, version, compileDeps, projectDeps,
                    generatedSourceDirs, hasScala, submodules));
        }

        project.getLogger().lifecycle("gradle2pom: pom.xml written to {}",
                pomFile.getAbsolutePath());
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

        Set<Dependency> deps = new HashSet<>();

        for (String name : new String[]{"implementation", "compileClasspath"}) {
            Configuration cfg = project.getConfigurations().findByName(name);
            if (cfg == null) {
                continue;
            }
            if (!cfg.isCanBeResolved()) {
                for (var dep : cfg.getAllDependencies()) {
                    deps.add(new Dependency(dep.getGroup(), dep.getName(), dep.getVersion()));
                }
            } else {
                try {
                    List<ResolvedArtifact> artifacts = new ArrayList<>();
                    Set<ResolvedDependency> firstLevel =
                            cfg.getResolvedConfiguration().getFirstLevelModuleDependencies();
                    collectArtifacts(firstLevel, new LinkedHashSet<>(), artifacts);
                    artifacts.stream().forEach( x -> {
                        var id = x.getModuleVersion().getId();
                        deps.add(new Dependency(id.getGroup(), id.getName(), id.getVersion()));
                    });
                } catch (Exception e) {
                    project.getLogger().debug(
                            "gradle2pom: could not resolve configuration '{}': {}", name, e.getMessage());
                }
            }
            /*

             */
        }
        return new ArrayList<>(deps);
        //return artifacts;
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
            seen.add(new Dependency(p.getGroup().toString(), p.getName(), p.getVersion().toString()));
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
    // Generated-source directories
    // -------------------------------------------------------------------------

    /**
     * Scans {@code build/generated/sources/<directory>/java} under the project directory
     * and returns the relative paths of directories that actually exist on disk.
     * Returns an empty list when no generated source directories are present
     * (the build-helper-maven-plugin is then omitted from the POM entirely).
     */
    private List<String> findGeneratedSourceDirs(Project project) {
        List<String> dirs = new ArrayList<>();

        File mainGenerated = new File(project.getProjectDir(), "build/generated/main/java");
        if (mainGenerated.isDirectory()){
            dirs.add(relativePath(project, mainGenerated));
        }

        File generatedSources = new File(project.getProjectDir(),
                "build/generated/sources");
        if (!generatedSources.isDirectory()) {
            // No generated sources exist yet — omit the build-helper plugin.
            return dirs;
        }

        File[] children = generatedSources.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                File javaDir = new File(child, "java");
                if (javaDir.isDirectory()) {
                    // Prefer the "main" sub-directory when it exists.
                    File mainDir = new File(javaDir, "main");
                    if (mainDir.isDirectory()) {
                        dirs.add(relativePath(project, mainDir));
                    } else {
                        dirs.add(relativePath(project, javaDir));
                    }
                }
            }
        }

        // dirs may still be empty if no */java sub-directory was found.
        return dirs;
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
                           List<String> submodules) {
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
        sb.append(" <maven.compiler.release>17</maven.compiler.release>\n");
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
