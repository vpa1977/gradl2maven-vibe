# Your task

Write GradleToPOMPlugin that generates pom.xml per gradle project.

# How it works

- Create pom.xml for each project
- Use scala-maven-plugin https://davidb.github.io/scala-maven-plugin/index.html to compile scala code.
- Add resolved compile classpath dependencies as compile dependencies. Use the `compileClasspath` Gradle configuration (resolvable; includes `implementation`, `compileOnly`, and annotation-processor API types). Fall back to `runtimeClasspath` if `compileClasspath` is unavailable.
- Add resolved annotationProcessor scope dependencies as compile dependencies (required so that generated sources can reference processor API types, e.g. Lombok, MapStruct). Deduplicate against compileClasspath deps.
- ensure that build/generated/sources/*/java added to sources. Use build-helper-maven-plugin with configuration similar to:
---
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>add-source</goal></goals>
            <configuration>
                <sources>
                    <source>build/generated/sources/annotationProcessor/java/main</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
---
    Replace build/generated/sources/annotationProcessor/java/main in the snippet with an actual path to the generated source IF generated sources are present. DO NOT ADD THIS IF PROJECT DOES NOT GENERATE SOURCES.
- plugin id is "com.github.vpa1977.gradle2pom"
- for the root project in private void generatePom(Project project) throws IOException include submodules for each child project
- root maven project should have pom packaging if submodules present
- export should add dependencies required for annotation processor generated source to be able to compile generated sources. Use compile scope
- export should add child projects as dependencies for the current one if the project is not a root project