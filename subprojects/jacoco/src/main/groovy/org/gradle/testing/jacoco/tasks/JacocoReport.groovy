/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.testing.jacoco.tasks

import org.gradle.api.GradleException
import org.gradle.api.Incubating
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTreeElement
import org.gradle.api.internal.project.IsolatedAntBuilder
import org.gradle.api.reporting.Reporting
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.*
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import org.gradle.internal.jacoco.JacocoReportsContainerImpl
import org.gradle.internal.reflect.Instantiator
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

import javax.inject.Inject

/**
 * Task to generate HTML, Xml and CSV reports of Jacoco coverage data.
 */
@Incubating
class JacocoReport extends JacocoBase implements Reporting<JacocoReportsContainer> {
    /**
     * Collection of execution data files to analyze.
     */
    @InputFiles
    FileCollection executionData

    /**
     * Source sets that coverage should be reported for.
     */
    @InputFiles
    FileCollection sourceDirectories

    /**
     * Source sets that coverage should be reported for.
     */
    @InputFiles
    FileCollection classDirectories

    /**
     * Additional class dirs that coverage data should be reported for.
     */
    @Optional
    @InputFiles
    FileCollection additionalClassDirs

    /**
     * Additional source dirs for the classes coverage data is being reported for.
     */
    @Optional
    @InputFiles
    FileCollection additionalSourceDirs

    @Nested
    private final JacocoReportsContainerImpl reports

    protected final PatternFilterable patternSet = new PatternSet()

    boolean ignoreFailures = false

    private Closure checkClosure

    JacocoReport() {
        reports = instantiator.newInstance(JacocoReportsContainerImpl, this)
        onlyIf { getExecutionData().every { it.exists() } } //TODO SF it should be 'any' instead of 'every'
    }

    @Inject
    protected Instantiator getInstantiator() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected IsolatedAntBuilder getAntBuilder() {
        throw new UnsupportedOperationException();
    }

    public void setCheck(Closure checkClosure) {
        this.checkClosure = checkClosure
    }

    public void include(String... includes) {
        patternSet.include(includes)
    }

    public void include(Iterable<String> includes) {
        patternSet.include(includes)
    }

    public void include(Spec<FileTreeElement> includeSpec) {
        patternSet.include(includeSpec)
    }

    public void include(Closure includeSpec) {
        patternSet.include(includeSpec)
    }

    public void exclude(String... excludes) {
        patternSet.exclude(excludes)
    }

    public void exclude(Iterable<String> excludes) {
        patternSet.exclude(excludes)
    }

    public void exclude(Spec<FileTreeElement> excludeSpec) {
        patternSet.exclude(excludeSpec)
    }

    public void exclude(Closure excludeSpec) {
        patternSet.exclude(excludeSpec)
    }

    @Override
    @TaskAction
    void generate() {
        def task = this
        String failures = null
        antBuilder.withClasspath(getJacocoClasspath()).execute {
            ant.taskdef(name: 'jacocoReport', classname: 'org.jacoco.ant.ReportTask')
            String violationsProperty = 'jacoco.violations'
            ant.jacocoReport {
                executiondata {
                    getExecutionData().addToAntBuilder(ant, 'resources')
                }
                structure(name: getProject().getName()) {
                    classfiles {
                        getAllClassDirs().filter { it.exists() }.asFileTree.matching(patternSet).addToAntBuilder(ant, 'resources')
                    }
                    sourcefiles {
                        getAllSourceDirs().filter { it.exists() }.addToAntBuilder(ant, 'resources')
                    }
                }
                if(reports.html.isEnabled()) {
                    html(destdir: reports.html.destination)
                }
                if(reports.xml.isEnabled()) {
                    xml(destfile: reports.xml.destination)
                }
                if(reports.csv.isEnabled()) {
                    csv(destfile: reports.csv.destination)
                }
                if (task.checkClosure!=null) {
                    def ch = delegate.check(task.checkClosure).getWrapper()
                    if (!ignoreFailures) ch.setAttribute('violationsproperty',violationsProperty)
                    ch.setAttribute('failonviolation',false)
                }
            }
            if (!ignoreFailures) {
                failures = ant.antProject.getProperty(violationsProperty)
            }
        }
        if (failures!=null) {
            throw new GradleException(failures)
        }
    }
    /**
     * Adds execution data files to be used during coverage
     * analysis.
     * @param files one or more files to add
     */
    void executionData(Object... files) {
        if (this.executionData == null) {
            this.executionData = getProject().files(files)
        } else {
            this.executionData += getProject().files(files)
        }
    }

    /**
     * Adds execution data generated by a task to the list
     * of those used during coverage analysis. Only tasks
     * with a {@link JacocoTaskExtension} will be included;
     * all others will be ignored.
     * @param tasks one or more tasks to add
     */
    void executionData(Task... tasks) {
        tasks.each { task ->
            JacocoTaskExtension extension = task.extensions.findByType(JacocoTaskExtension)
            if (extension != null) {
                executionData({ extension.destinationFile })
                mustRunAfter(task)
            }
        }
    }

    /**
     * Adds execution data generated by the given tasks to
     * the list of those used during coverage analysis.
     * Only tasks with a {@link JacocoTaskExtension} will
     * be included; all others will be ignored.
     * @param tasks one or more tasks to add
     */
    void executionData(TaskCollection tasks) {
        tasks.all { executionData(it) }
    }

    /**
     * Gets the class directories that coverage will
     * be reported for. All classes in these directories
     * will be included in the report.
     * @return class dirs to report coverage of
     */
    FileCollection getAllClassDirs() {
        def additionalDirs = getAdditionalClassDirs()
        if (additionalDirs == null) {
            return classDirectories
        }
        return classDirectories + getAdditionalClassDirs()
    }

    /**
     * Gets the source directories for the classes that will
     * be reported on. Source will be obtained from these
     * directories only for the classes included in the report.
     * @return source directories for the classes reported on
     * @see #getAllClassDirs()
     */
    FileCollection getAllSourceDirs() {
        def additionalDirs = getAdditionalSourceDirs()
        if (additionalDirs == null) {
            return sourceDirectories
        }
        return sourceDirectories + getAdditionalSourceDirs()
    }

    /**
     * Adds a source set to the list to be reported on. The
     * output of this source set will be used as classes to
     * include in the report. The source for this source set
     * will be used for any classes included in the report.
     * @param sourceSets one or more source sets to report on
     */
    void sourceSets(SourceSet... sourceSets) {
        getProject().afterEvaluate {
            sourceSets.each { sourceSet ->
                if (this.sourceDirectories == null) {
                    this.sourceDirectories = getProject().files(sourceSet.allJava.getSrcDirs())
                } else {
                    this.sourceDirectories = this.sourceDirectories + getProject().files(sourceSet.allJava.getSrcDirs())
                }
                if (this.classDirectories == null) {
                    this.classDirectories = sourceSet.output
                } else {
                    this.classDirectories = this.classDirectories + sourceSet.output
                }
            }
        }
    }

    /**
     * Adds additional class directories to those
     * that will be included in the report.
     * @param dirs one or more directories containing
     * classes to report coverage of
     */
    void additionalClassDirs(File... dirs) {
        additionalClassDirs(getProject().files(dirs))
    }

    /**
     * Adds additional class directories to those
     * that will be included in the report.
     * @param dirs a {@code FileCollection} of directories
     * containing classes to report coverage of
     */
    void additionalClassDirs(FileCollection dirs) {
        if (this.additionalClassDirs == null) {
            this.additionalClassDirs = dirs
        } else {
            this.additionalClassDirs += dirs
        }
    }

    /**
     * Adds additional source directories to be used
     * for any classes included in the report.
     * @param dirs one or more directories containing
     * source files for the classes included in the report
     */
    void additionalSourceDirs(File... dirs) {
        additionalSourceDirs(getProject().files(dirs))
    }

    /**
     * Adds additional source directories to be used
     * for any classes included in the report.
     * @param dirs a {@code FileCollection} of directories
     * containing source files for the classes included in
     * the report
     */
    void additionalSourceDirs(FileCollection dirs) {
        if (this.additionalSourceDirs == null) {
            this.additionalSourceDirs = dirs
        } else {
            this.additionalSourceDirs += dirs
        }
    }

    /**
     * Configures the reports to be generated by this task.
     */
    JacocoReportsContainer reports(Closure closure) {
        reports.configure(closure)
    }

    /**
     * Returns the reports to be generated by this task.
     */
    JacocoReportsContainer getReports() {
        return reports
    }

}
