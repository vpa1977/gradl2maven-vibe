# Your task

Implement support for scalaCompiler task.

# Specification

Compiles Scala source files, and optionally, Java source files.

Properties:

1. classpath

The classpath to use to compile the source files.

2. destinationDirectory

The directory property that represents the directory to generate the .class files into.

3. excludes

The set of exclude patterns.
4. includes

The set of include patterns.
5. javaLauncher

The toolchain JavaLauncher to use for executing the Scala compiler.
options

The Java compilation options.
6. scalaClasspath

The classpath to use to load the Scala compiler.
7. scalaCompileOptions

The Scala compilation options.
8. scalaCompilerPlugins

The Scala compiler plugins to use.
9. source

The source for this task, after the include and exclude patterns have been applied. Ignores source files which do not exist.
10. sourceCompatibility

The Java language level to use to compile the source files.
11. targetCompatibility

The target JVM to generate the .class files for.

# Notes

Do not use reflection, the plugin is present on the classpath.
