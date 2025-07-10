# open-in-run-config-idea-plugin

![Build](https://github.com/gschrader/open-in-run-config-idea-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/27881.svg)](https://plugins.jetbrains.com/plugin/27881)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/27881.svg)](https://plugins.jetbrains.com/plugin/27881)

## Plugin Overview

<!-- Plugin description -->
The **Open in Run Config** plugin allows you to quickly run any Java application configuration with a selected file as a program argument. It's essentially a productivity tool that eliminates the need to manually edit run configurations when you want to pass a file path to your application.
<!-- Plugin description end -->

## What It Does

1. **Context Menu Integration**: Adds a "Project Application" action to the context menu when you right-click on a file in the Project view or other file browsers in IntelliJ IDEA.

2. **Configuration Selection**: When triggered, it shows a popup list of all available Java Application run configurations in your project.

3. **Automatic File Path Injection**: Once you select a run configuration, it:
  - Creates a temporary copy of the selected configuration
  - Automatically adds the selected file's path as a program argument
  - Runs the configuration immediately

4. **Smart Handling**:
  - Only works with Java Application configurations (not other types like JUnit, etc.)
  - Preserves all original configuration settings (working directory, environment variables, etc.)
  - Creates temporary configurations that don't clutter your permanent run configurations
  - Handles both cases where the configuration already has program arguments and where it doesn't

## Use Cases

This plugin is particularly useful when you have applications that:
- Process files (like parsers, converters, analyzers)
- Need file paths as command-line arguments
- Are frequently run with different input files

Instead of manually editing run configurations each time, you can simply right-click on any file and run your application with that file as input.
