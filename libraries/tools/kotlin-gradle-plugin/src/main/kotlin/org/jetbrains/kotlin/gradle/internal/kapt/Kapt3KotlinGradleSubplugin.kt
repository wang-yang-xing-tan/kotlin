/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.internal

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.SourceKind
import com.intellij.openapi.util.SystemInfo
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.CLASS_STRUCTURE_ARTIFACT_TYPE
import org.jetbrains.kotlin.gradle.internal.kapt.incremental.StructureArtifactTransform
import org.jetbrains.kotlin.gradle.model.builder.KaptModelBuilder
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTaskData
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.locateTask
import org.jetbrains.kotlin.gradle.tasks.registerTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.*
import java.util.concurrent.Callable
import javax.inject.Inject

// apply plugin: 'kotlin-kapt'
class Kapt3GradleSubplugin @Inject internal constructor(private val registry: ToolingModelBuilderRegistry) :
    KotlinCompilerPluginSupportPlugin {

    override fun apply(project: Project) {
        project.extensions.create("kapt", KaptExtension::class.java)

        project.configurations.create(KAPT_WORKER_DEPENDENCIES_CONFIGURATION_NAME).apply {
            project.getKotlinPluginVersion()?.let { kotlinPluginVersion ->
                val kaptDependency = getPluginArtifact().run { "$groupId:$artifactId:$kotlinPluginVersion" }
                dependencies.add(project.dependencies.create(kaptDependency))
            } ?: throw GradleException("Kotlin plugin should be enabled before 'kotlin-kapt'")
        }

        registry.register(KaptModelBuilder())
    }

    companion object {
        @JvmStatic
        fun getKaptGeneratedClassesDir(project: Project, sourceSetName: String) =
            File(project.buildDir, "tmp/kapt3/classes/$sourceSetName")

        @JvmStatic
        fun getKaptGeneratedSourcesDir(project: Project, sourceSetName: String) =
            File(project.buildDir, "generated/source/kapt/$sourceSetName")

        @JvmStatic
        fun getKaptGeneratedKotlinSourcesDir(project: Project, sourceSetName: String) =
            File(project.buildDir, "generated/source/kaptKotlin/$sourceSetName")

        private val VERBOSE_OPTION_NAME = "kapt.verbose"
        private val USE_WORKER_API = "kapt.use.worker.api"
        private val INFO_AS_WARNINGS = "kapt.info.as.warnings"
        private val INCLUDE_COMPILE_CLASSPATH = "kapt.include.compile.classpath"
        private val INCREMENTAL_APT = "kapt.incremental.apt"

        const val KAPT_WORKER_DEPENDENCIES_CONFIGURATION_NAME = "kotlinKaptWorkerDependencies"

        private val KAPT_KOTLIN_GENERATED = "kapt.kotlin.generated"

        val MAIN_KAPT_CONFIGURATION_NAME = "kapt"

        const val KAPT_ARTIFACT_NAME = "kotlin-annotation-processing-gradle"
        val KAPT_SUBPLUGIN_ID = "org.jetbrains.kotlin.kapt3"

        fun getKaptConfigurationName(sourceSetName: String): String {
            return if (sourceSetName != SourceSet.MAIN_SOURCE_SET_NAME)
                "$MAIN_KAPT_CONFIGURATION_NAME${sourceSetName.capitalize()}"
            else
                MAIN_KAPT_CONFIGURATION_NAME
        }

        fun Project.findKaptConfiguration(sourceSetName: String): Configuration? {
            return project.configurations.findByName(getKaptConfigurationName(sourceSetName))
        }

        fun Project.isKaptVerbose(): Boolean {
            return hasProperty(VERBOSE_OPTION_NAME) && property(VERBOSE_OPTION_NAME) == "true"
        }

        fun Project.isUseWorkerApi(): Boolean {
            return !(hasProperty(USE_WORKER_API) && property(USE_WORKER_API) == "false")
        }

        fun Project.isIncrementalKapt(): Boolean {
            return !(hasProperty(INCREMENTAL_APT) && property(INCREMENTAL_APT) == "false")
        }

        fun Project.isInfoAsWarnings(): Boolean {
            return hasProperty(INFO_AS_WARNINGS) && property(INFO_AS_WARNINGS) == "true"
        }

        fun includeCompileClasspath(project: Project): Boolean? =
            project.findProperty(INCLUDE_COMPILE_CLASSPATH)?.run { toString().toBoolean() }

        fun findMainKaptConfiguration(project: Project) = project.findKaptConfiguration(SourceSet.MAIN_SOURCE_SET_NAME)

        fun createAptConfigurationIfNeeded(project: Project, sourceSetName: String): Configuration {
            val configurationName = getKaptConfigurationName(sourceSetName)

            project.configurations.findByName(configurationName)?.let { return it }
            val aptConfiguration = project.configurations.create(configurationName).apply {
                // Should not be available for consumption from other projects during variant-aware dependency resolution:
                isCanBeConsumed = false
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            }

            if (aptConfiguration.name != MAIN_KAPT_CONFIGURATION_NAME) {
                // The main configuration can be created after the current one. We should handle this case
                val mainConfiguration = findMainKaptConfiguration(project)
                    ?: createAptConfigurationIfNeeded(project, SourceSet.MAIN_SOURCE_SET_NAME)

                aptConfiguration.extendsFrom(mainConfiguration)
            }

            return aptConfiguration
        }

        fun isEnabled(project: Project) =
            project.plugins.any { it is Kapt3GradleSubplugin }
    }

    private val kotlinToKaptGenerateStubsTasksMap = mutableMapOf<KotlinCompilation<*>, TaskProvider<KaptGenerateStubsTask>>()

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>) =
        (kotlinCompilation.platformType == KotlinPlatformType.jvm || kotlinCompilation.platformType == KotlinPlatformType.androidJvm)

    private fun Kapt3SubpluginContext.getKaptStubsDir() = temporaryKaptDirectory("stubs")

    private fun Kapt3SubpluginContext.getKaptIncrementalDataDir() = temporaryKaptDirectory("incrementalData", doMkDirs = false)

    private fun Kapt3SubpluginContext.getKaptIncrementalAnnotationProcessingCache() = temporaryKaptDirectory("incApCache")

    private fun Kapt3SubpluginContext.temporaryKaptDirectory(name: String, doMkDirs: Boolean = true): File {
        val dir = File(project.buildDir, "tmp/kapt3/$name/$sourceSetName")
        if (doMkDirs) {
            dir.mkdirs()
        }
        return dir
    }

    internal inner class Kapt3SubpluginContext(
        val project: Project,
        val javaCompile: TaskProvider<out AbstractCompile>?,
        val variantData: Any?,
        val sourceSetName: String,
        val kotlinCompilation: KotlinCompilation<*>,
        val kaptExtension: KaptExtension,
        val kaptClasspathConfigurations: List<Configuration>
    ) {
        val sourcesOutputDir = getKaptGeneratedSourcesDir(project, sourceSetName)
        val kotlinSourcesOutputDir = getKaptGeneratedKotlinSourcesDir(project, sourceSetName)
        val classesOutputDir = getKaptGeneratedClassesDir(project, sourceSetName)
        val includeCompileClasspath =
            kaptExtension.includeCompileClasspath
                ?: includeCompileClasspath(project)
                ?: true

        val kotlinCompile: TaskProvider<KotlinCompile>
            // Can't use just kotlinCompilation.compileKotlinTaskProvider, as the latter is not statically-known to be KotlinCompile
            get() = checkNotNull(project.locateTask(kotlinCompilation.compileKotlinTaskName))
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        val buildDependencies = arrayListOf<TaskDependency>()
        val kaptConfigurations = arrayListOf<Configuration>()

        fun handleSourceSet(sourceSetName: String) {
            project.findKaptConfiguration(sourceSetName)?.let { kaptConfiguration ->
                kaptConfigurations += kaptConfiguration
                buildDependencies += kaptConfiguration.buildDependencies
            }
        }

        val androidVariantData: BaseVariant? = (kotlinCompilation as? KotlinJvmAndroidCompilation)?.androidVariant

        val sourceSetName = if (androidVariantData != null) {
            for (provider in androidVariantData.sourceSets) {
                handleSourceSet((provider as AndroidSourceSet).name)
            }
            androidVariantData.name
        } else {
            handleSourceSet(kotlinCompilation.compilationName)
            kotlinCompilation.compilationName
        }

        val kaptExtension = project.extensions.getByType(KaptExtension::class.java)

        val nonEmptyKaptConfigurations = kaptConfigurations.filter { it.dependencies.isNotEmpty() }

        val javaCompileOrNull = findJavaTaskForKotlinCompilation(kotlinCompilation)

        val context = Kapt3SubpluginContext(
            project, javaCompileOrNull,
            androidVariantData, sourceSetName, kotlinCompilation, kaptExtension, nonEmptyKaptConfigurations
        )

        val kaptGenerateStubsTaskProvider: TaskProvider<KaptGenerateStubsTask> = context.createKaptGenerateStubsTask()
        val kaptTaskProvider: TaskProvider<out KaptTask> = context.createKaptKotlinTask(useWorkerApi = project.isUseWorkerApi())

        kaptGenerateStubsTaskProvider.configure { kaptGenerateStubsTask ->
            kaptGenerateStubsTask.source(*kaptConfigurations.toTypedArray())

            kaptGenerateStubsTask.dependsOn(*buildDependencies.toTypedArray())
            kaptGenerateStubsTask.dependsOn(
                project.provider {
                    kotlinCompilation.compileKotlinTask.dependsOn.filter { it !is TaskProvider<*> || it.name != kaptTaskProvider.name }
                }
            )

            if (androidVariantData != null) {
                kaptGenerateStubsTask.inputs.files(
                    Callable {
                        // Avoid circular dependency: the stubs task need the Java sources, but the Java sources generated by Kapt should be
                        // excluded, as the Kapt tasks depend on the stubs ones, and having them in the input would lead to a cycle
                        val kaptJavaOutput = kaptTaskProvider.get().destinationDir
                        androidVariantData.getSourceFolders(SourceKind.JAVA).filter { it.dir != kaptJavaOutput }
                    }
                ).withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }

        kaptTaskProvider.configure { kaptTask ->
            kaptTask.dependsOn(kaptGenerateStubsTaskProvider)
        }
        context.kotlinCompile.configure { it.dependsOn(kaptTaskProvider) }

        /** Plugin options are applied to kapt*Compile inside [createKaptKotlinTask] */
        return project.provider { emptyList<SubpluginOption>() }
    }

    override fun getPluginKotlinTasks(compilation: KotlinCompilation<*>): List<TaskProvider<out AbstractCompile>> {
        val kaptGenerateStubsTask = kotlinToKaptGenerateStubsTasksMap[compilation]
        return listOfNotNull(kaptGenerateStubsTask)
    }

    // This method should be called no more than once for each Kapt3SubpluginContext
    private fun Kapt3SubpluginContext.buildOptions(
        aptMode: String,
        javacOptions: Provider<Map<String, String>>
    ): Provider<List<SubpluginOption>> {
        disableAnnotationProcessingInJavaTask()

        return project.provider {
            val pluginOptions = mutableListOf<SubpluginOption>()

            val generatedFilesDir = getKaptGeneratedSourcesDir(project, sourceSetName)
            KaptWithAndroid.androidVariantData(this)?.addJavaSourceFoldersToModel(generatedFilesDir)

            pluginOptions += SubpluginOption("aptMode", aptMode)

            pluginOptions += FilesSubpluginOption("sources", listOf(generatedFilesDir))
            pluginOptions += FilesSubpluginOption("classes", listOf(getKaptGeneratedClassesDir(project, sourceSetName)))

            pluginOptions += FilesSubpluginOption("incrementalData", listOf(getKaptIncrementalDataDir()))

            val annotationProcessors = kaptExtension.processors
            if (annotationProcessors.isNotEmpty()) {
                pluginOptions += SubpluginOption("processors", annotationProcessors)
            }

            kotlinSourcesOutputDir.mkdirs()

            val apOptions = getAPOptions().get()

            pluginOptions += CompositeSubpluginOption(
                "apoptions",
                lazy { encodeList(apOptions.associate { it.key to it.value }) },
                apOptions
            )

            pluginOptions += SubpluginOption("javacArguments", encodeList(javacOptions.get()))

            pluginOptions += SubpluginOption("includeCompileClasspath", includeCompileClasspath.toString())

            addMiscOptions(pluginOptions)

            pluginOptions
        }
    }

    private fun Kapt3SubpluginContext.getAPOptions(): Provider<List<SubpluginOption>> = project.provider {
        val androidVariantData = KaptWithAndroid.androidVariantData(this)

        val androidPlugin = androidVariantData?.let {
            project.extensions.findByName("android") as? BaseExtension
        }

        val androidOptions = androidVariantData?.annotationProcessorOptions ?: emptyMap()

        val apOptionsFromProviders =
            androidVariantData?.annotationProcessorOptionProviders
                ?.flatMap { (it as CommandLineArgumentProvider).asArguments() }
                .orEmpty()

        val subluginOptionsFromProvidedApOptions = apOptionsFromProviders.map {
            // Use the internal subplugin option type to exclude them from Gradle input/output checks, as their providers are already
            // properly registered as a nested input:

            // Pass options as they are in the key-only form (key = 'a=b'), kapt will deal with them:
            InternalSubpluginOption(key = it.removePrefix("-A"), value = "")
        }

        val apOptionsPairsList: List<Pair<String, String>> =
            kaptExtension.getAdditionalArguments(project, androidVariantData, androidPlugin).toList() +
                    androidOptions.toList()

        apOptionsPairsList.map { SubpluginOption(it.first, it.second) } +
                FilesSubpluginOption(KAPT_KOTLIN_GENERATED, listOf(kotlinSourcesOutputDir)) +
                subluginOptionsFromProvidedApOptions
    }

    private fun Kapt3SubpluginContext.registerSubpluginOptions(
        taskProvider: TaskProvider<*>,
        optionsProvider: Provider<List<SubpluginOption>>
    ) {
        taskProvider.configure { taskInstance ->
            val container = when (taskInstance) {
                is KaptGenerateStubsTask -> taskInstance.pluginOptions
                is KaptWithKotlincTask -> taskInstance.pluginOptions
                is KaptWithoutKotlincTask -> taskInstance.processorOptions
                else -> error("Unexpected task ${taskInstance.name} (${taskInstance.javaClass})")
            }


            val compilerPluginId = getCompilerPluginId()

            val options = optionsProvider.get()

            taskInstance.registerSubpluginOptionsAsInputs(compilerPluginId, options)

            for (option in options) {
                container.addPluginArgument(compilerPluginId, option)
            }
        }

        // Also register all the subplugin options from the Kotlin task:
        project.whenEvaluated {
            taskProvider.configure { taskInstance ->
                kotlinCompile.get().pluginOptions.subpluginOptionsByPluginId.forEach { (pluginId, options) ->
                    taskInstance.registerSubpluginOptionsAsInputs("kotlinCompile.$pluginId", options)
                }
            }
        }
    }

    private fun encodeList(options: Map<String, String>): String {
        val os = ByteArrayOutputStream()
        val oos = ObjectOutputStream(os)

        oos.writeInt(options.size)
        for ((key, value) in options.entries) {
            oos.writeUTF(key)
            oos.writeUTF(value)
        }

        oos.flush()
        return Base64.getEncoder().encodeToString(os.toByteArray())
    }

    private fun Kapt3SubpluginContext.addMiscOptions(pluginOptions: MutableList<SubpluginOption>) {
        if (kaptExtension.generateStubs) {
            project.logger.warn("'kapt.generateStubs' is not used by the 'kotlin-kapt' plugin")
        }

        pluginOptions += SubpluginOption("useLightAnalysis", "${kaptExtension.useLightAnalysis}")
        pluginOptions += SubpluginOption("correctErrorTypes", "${kaptExtension.correctErrorTypes}")
        pluginOptions += SubpluginOption("dumpDefaultParameterValues", "${kaptExtension.dumpDefaultParameterValues}")
        pluginOptions += SubpluginOption("mapDiagnosticLocations", "${kaptExtension.mapDiagnosticLocations}")
        pluginOptions += SubpluginOption("strictMode", "${kaptExtension.strictMode}")
        pluginOptions += SubpluginOption("showProcessorTimings", "${kaptExtension.showProcessorTimings}")
        pluginOptions += SubpluginOption("detectMemoryLeaks", kaptExtension.detectMemoryLeaks)
        pluginOptions += SubpluginOption("infoAsWarnings", "${project.isInfoAsWarnings()}")
        pluginOptions += FilesSubpluginOption("stubs", listOf(getKaptStubsDir()))

        if (project.isKaptVerbose()) {
            pluginOptions += SubpluginOption("verbose", "true")
        }
    }

    private fun Kapt3SubpluginContext.createKaptKotlinTask(useWorkerApi: Boolean): TaskProvider<out KaptTask> {
        val taskClass = if (useWorkerApi) KaptWithoutKotlincTask::class.java else KaptWithKotlincTask::class.java
        val taskName = getKaptTaskName("kapt")

        var classStructureIfIncremental: Configuration? = null

        if (project.isIncrementalKapt()) {
            maybeRegisterTransform(project)

            classStructureIfIncremental = project.configurations.create("_classStructure${taskName}")

            // Wrap the `kotlinCompile.classpath` into a file collection, so that, if the classpath is represented by a configuration,
            // the configuration is not extended (via extendsFrom, which normally happens when one configuration is _added_ into another)
            // but is instead included as the (lazily) resolved files. This is needed because the class structure configuration doesn't have
            // the attributes that are potentially needed to resolve dependencies on MPP modules, and the classpath configuration does.
            project.dependencies.add(classStructureIfIncremental.name, project.files(project.provider { kotlinCompile.get().classpath }))
        }

        val kaptTaskProvider = project.registerTask(taskName, taskClass, emptyList()) { kaptTask ->
            kaptTask.useBuildCache = kaptExtension.useBuildCache

            kaptTask.kotlinCompileTask = kotlinCompilation.compileKotlinTaskProvider.get() as KotlinCompile

            kaptTask.stubsDir = getKaptStubsDir()

            kaptTask.destinationDir = sourcesOutputDir
            kaptTask.kotlinSourcesDestinationDir = kotlinSourcesOutputDir
            kaptTask.classesDir = classesOutputDir
            kaptTask.includeCompileClasspath = includeCompileClasspath

            kaptTask.isIncremental = project.isIncrementalKapt()

            if (kaptTask.isIncremental) {
                kaptTask.incAptCache = getKaptIncrementalAnnotationProcessingCache()
                kaptTask.localState.register(kaptTask.incAptCache)

                kaptTask.classpathStructure = classStructureIfIncremental!!.incoming.artifactView { viewConfig ->
                    viewConfig.attributes.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
                }.files

                if (kaptTask is KaptWithKotlincTask) {
                    kaptTask.pluginOptions.addPluginArgument(
                        getCompilerPluginId(),
                        SubpluginOption("incrementalCache", kaptTask.incAptCache!!.absolutePath)
                    )
                }
            }

            kaptTask.kaptClasspathConfigurations = kaptClasspathConfigurations

            KaptWithAndroid.androidVariantData(this)?.annotationProcessorOptionProviders?.let {
                kaptTask.annotationProcessorOptionProviders.add(it)
            }
        }

        kotlinCompilation.output.apply {
            addClassesDir { project.files(classesOutputDir).builtBy(kaptTaskProvider) }
        }

        kotlinCompilation.compileKotlinTaskProvider.configure {
            it as SourceTask
            it.source(sourcesOutputDir, kotlinSourcesOutputDir)
        }

        if (javaCompile != null) {
            val androidVariantData = KaptWithAndroid.androidVariantData(this)
            if (androidVariantData != null) {
                KaptWithAndroid.registerGeneratedJavaSourceForAndroid(this, project, androidVariantData, kaptTaskProvider)
            } else {
                registerGeneratedJavaSource(kaptTaskProvider, javaCompile)
            }
        }

        val dslJavacOptions: Provider<Map<String, String>> = project.provider {
            kaptExtension.getJavacOptions().toMutableMap().also { result ->
                if (javaCompile != null && "-source" !in result && "--source" !in result && "--release" !in result) {
                    val sourceOptionKey = if (SystemInfo.isJavaVersionAtLeast(12, 0, 0)) {
                        "--source"
                    } else {
                        "-source"
                    }
                    result[sourceOptionKey] = javaCompile.get().sourceCompatibility
                }
            }
        }

        if (taskClass == KaptWithKotlincTask::class.java) {
            val subpluginOptions = buildOptions("apt", dslJavacOptions)
            registerSubpluginOptions(kaptTaskProvider, subpluginOptions)
        }

        if (taskClass == KaptWithoutKotlincTask::class.java) {
            kaptTaskProvider.configure {
                it as KaptWithoutKotlincTask
                it.isVerbose = project.isKaptVerbose()
                it.mapDiagnosticLocations = kaptExtension.mapDiagnosticLocations
                it.annotationProcessorFqNames = kaptExtension.processors.split(',').filter { it.isNotEmpty() }
                it.javacOptions = dslJavacOptions.get()
            }

            val subpluginOptions = getAPOptions()
            registerSubpluginOptions(kaptTaskProvider, subpluginOptions)
        }

        return kaptTaskProvider
    }

    private fun maybeRegisterTransform(project: Project) {
        if (!project.extensions.extraProperties.has("KaptStructureTransformAdded")) {
            project.dependencies.registerTransform { variantTransform ->
                variantTransform.artifactTransform(StructureArtifactTransform::class.java)
                variantTransform.from.attribute(artifactType, "jar")
                variantTransform.to.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
            }
            project.dependencies.registerTransform { variantTransform ->
                variantTransform.artifactTransform(StructureArtifactTransform::class.java)
                variantTransform.from.attribute(artifactType, "directory")
                variantTransform.to.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
            }

            project.extensions.extraProperties["KaptStructureTransformAdded"] = true
        }
    }

    private fun Kapt3SubpluginContext.createKaptGenerateStubsTask(): TaskProvider<KaptGenerateStubsTask> {
        val kaptTaskName = getKaptTaskName("kaptGenerateStubs")

        KotlinCompileTaskData.register(kaptTaskName, kotlinCompilation).apply {
            useModuleDetection.set(KotlinCompileTaskData.get(project, kotlinCompile.name).useModuleDetection)
            destinationDir.set(project.provider { getKaptIncrementalDataDir() })
        }

        val kaptTaskProvider = project.registerTask<KaptGenerateStubsTask>(kaptTaskName) { kaptTask ->
            kaptTask.kotlinCompileTask = kotlinCompile.get()

            kaptTask.stubsDir = getKaptStubsDir()
            kaptTask.setDestinationDir { getKaptIncrementalDataDir() }
            kaptTask.mapClasspath { kaptTask.kotlinCompileTask.classpath }
            kaptTask.generatedSourcesDir = sourcesOutputDir

            kaptTask.kaptClasspathConfigurations = kaptClasspathConfigurations

            PropertiesProvider(project).mapKotlinTaskProperties(kaptTask)
        }

        kotlinToKaptGenerateStubsTasksMap[kotlinCompilation] = kaptTaskProvider

        val subpluginOptions = buildOptions("stubs", project.provider { kaptExtension.getJavacOptions() })
        registerSubpluginOptions(kaptTaskProvider, subpluginOptions)

        return kaptTaskProvider
    }

    private fun Kapt3SubpluginContext.getKaptTaskName(prefix: String): String {
        // Replace compile*Kotlin to kapt*Kotlin
        val baseName = kotlinCompile.name
        assert(baseName.startsWith("compile"))
        return baseName.replaceFirst("compile", prefix)
    }

    private fun Kapt3SubpluginContext.disableAnnotationProcessingInJavaTask() {
        javaCompile?.configure { javaCompileInstance ->
            if (javaCompileInstance !is JavaCompile)
                return@configure

            val options = javaCompileInstance.options
            // 'android-apt' (com.neenbedankt) adds a File instance to compilerArgs (List<String>).
            // Although it's not our problem, we need to handle this case properly.
            val oldCompilerArgs: List<Any> = options.compilerArgs
            val newCompilerArgs = oldCompilerArgs.filterTo(mutableListOf()) {
                it !is CharSequence || !it.toString().startsWith("-proc:")
            }
            newCompilerArgs.add("-proc:none")
            @Suppress("UNCHECKED_CAST")
            options.compilerArgs = newCompilerArgs as List<String>

            // Filter out the argument providers that are related to annotation processing and therefore already used by Kapt.
            // This is done to avoid outputs intersections between Kapt and and javaCompile and make the up-to-date check for
            // javaCompile more granular as it does not perform annotation processing:
            KaptWithAndroid.androidVariantData(this)?.let { androidVariantData ->
                options.compilerArgumentProviders.removeAll(androidVariantData.annotationProcessorOptionProviders)
            }
        }
    }

    override fun getCompilerPluginId() = KAPT_SUBPLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        JetBrainsSubpluginArtifact(artifactId = KAPT_ARTIFACT_NAME)
}
private val artifactType = Attribute.of("artifactType", String::class.java)

// Don't reference the BaseVariant type in the Kapt plugin signatures, as those type references will fail to link when there's no Android
// Gradle plugin on the project's plugin classpath
private object KaptWithAndroid {
    // Avoid loading the BaseVariant type at call sites and instead lazily load it when evaluation reaches it in the body using inline:
    @Suppress("NOTHING_TO_INLINE")
    inline fun androidVariantData(context: Kapt3GradleSubplugin.Kapt3SubpluginContext): BaseVariant? = context.variantData as? BaseVariant

    @Suppress("NOTHING_TO_INLINE")
    // Avoid loading the BaseVariant type at call sites and instead lazily load it when evaluation reaches it in the body using inline:
    inline fun registerGeneratedJavaSourceForAndroid(
        kapt3SubpluginContext: Kapt3GradleSubplugin.Kapt3SubpluginContext,
        project: Project,
        variantData: BaseVariant,
        kaptTask: TaskProvider<out KaptTask>
    ) {
        val kaptSourceOutput = project.fileTree(kapt3SubpluginContext.sourcesOutputDir).builtBy(kaptTask)
        kaptSourceOutput.include("**/*.java")
        variantData.registerExternalAptJavaOutput(kaptSourceOutput)
        kaptTask.configure { kaptTaskInstance ->
            variantData.dataBindingDependencyArtifactsIfSupported?.let { dataBindingArtifacts ->
                kaptTaskInstance.dependsOn(dataBindingArtifacts)
            }
        }
    }
}

internal fun registerGeneratedJavaSource(kaptTask: TaskProvider<out KaptTask>, javaTaskProvider: TaskProvider<out AbstractCompile>) {
    javaTaskProvider.configure { javaTask ->
        val generatedJavaSources = javaTask.project.fileTree(kaptTask.map { it.destinationDir })
        generatedJavaSources.include("**/*.java")
        javaTask.source(generatedJavaSources)
    }
}

internal fun Configuration.getNamedDependencies(): List<Dependency> = allDependencies.filter { it.group != null && it.name != null }

private val ANNOTATION_PROCESSOR = "annotationProcessor"
private val ANNOTATION_PROCESSOR_CAP = ANNOTATION_PROCESSOR.capitalize()

internal fun checkAndroidAnnotationProcessorDependencyUsage(project: Project) {
    if (project.hasProperty("kapt.dont.warn.annotationProcessor.dependencies")) {
        return
    }

    val isKapt3Enabled = Kapt3GradleSubplugin.isEnabled(project)

    val apConfigurations = project.configurations
        .filter { it.name == ANNOTATION_PROCESSOR || (it.name.endsWith(ANNOTATION_PROCESSOR_CAP) && !it.name.startsWith("_")) }

    val problemDependencies = mutableListOf<Dependency>()

    for (apConfiguration in apConfigurations) {
        val apConfigurationName = apConfiguration.name

        val kaptConfigurationName = when (apConfigurationName) {
            ANNOTATION_PROCESSOR -> "kapt"
            else -> {
                val configurationName = apConfigurationName.dropLast(ANNOTATION_PROCESSOR_CAP.length)
                Kapt3GradleSubplugin.getKaptConfigurationName(configurationName)
            }
        }

        val kaptConfiguration = project.configurations.findByName(kaptConfigurationName) ?: continue
        val kaptConfigurationDependencies = kaptConfiguration.getNamedDependencies()

        problemDependencies += apConfiguration.getNamedDependencies().filter { a ->
            // Ignore annotationProcessor dependencies if they are also declared as 'kapt'
            kaptConfigurationDependencies.none { k -> a.group == k.group && a.name == k.name && a.version == k.version }
        }
    }

    if (problemDependencies.isNotEmpty()) {
        val artifactsRendered = problemDependencies.joinToString { "'${it.group}:${it.name}:${it.version}'" }
        val andApplyKapt = if (isKapt3Enabled) "" else " and apply the kapt plugin: \"apply plugin: 'kotlin-kapt'\""

        project.logger.warn(
            "${project.name}: " +
                    "'annotationProcessor' dependencies won't be recognized as kapt annotation processors. " +
                    "Please change the configuration name to 'kapt' for these artifacts: $artifactsRendered$andApplyKapt."
        )
    }
}
