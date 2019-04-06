package org.gradle.profiler

import org.gradle.tooling.model.idea.IdeaProject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.profiler.ScenarioLoader.loadScenarios

class ScenarioLoaderTest extends Specification {
    @Rule
    TemporaryFolder tmpDir = new TemporaryFolder()

    File projectDir
    File gradleUserHomeDir
    File outputDir
    File scenarioFile

    def setup() {
        projectDir = tmpDir.newFolder()
        outputDir = tmpDir.newFolder()
        scenarioFile = tmpDir.newFile()
    }

    private settings(Invoker invoker = Invoker.Cli, boolean benchmark = true, Integer warmups = null, Integer iterations = null) {
        new InvocationSettings(projectDir, Profiler.NONE, benchmark, outputDir, invoker, false, scenarioFile, [], [], [:], gradleUserHomeDir, warmups, iterations, false)
    }

    def "can load single scenario"() {
        def settings = settings()

        scenarioFile << """
            default {
                tasks = ["help"]
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["default"]
        def scenario = scenarios[0] as GradleScenarioDefinition
        scenario.action.tasks == ["help"]
        scenario.cleanupAction == BuildAction.NO_OP
    }

    def "can load single scenario with no tasks defined"() {
        def settings = settings()
        settings.targets.add("default") // don't use the target as the default tasks

        scenarioFile << """
            default {
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        def scenario = scenarios[0] as GradleScenarioDefinition
        scenario.action.tasks.empty
    }

    def "scenario uses invoker specified on command-line when none is specified"() {
        scenarioFile << """
            default {
            }
            withInvoker {
                run-using = tooling-api
            }
        """
        def settings1 = settings(Invoker.ToolingApi)
        def settings2 = settings(Invoker.CliColdDaemon)

        expect:
        def scenarios1 = loadScenarios(scenarioFile, settings1, Mock(GradleBuildConfigurationReader))
        (scenarios1[0] as GradleScenarioDefinition).invoker == Invoker.ToolingApi
        (scenarios1[1] as GradleScenarioDefinition).invoker == Invoker.ToolingApi

        def scenarios2 = loadScenarios(scenarioFile, settings2, Mock(GradleBuildConfigurationReader))
        (scenarios2[0] as GradleScenarioDefinition).invoker == Invoker.CliColdDaemon
        (scenarios2[1] as GradleScenarioDefinition).invoker == Invoker.ToolingApi
    }

    def "scenario can define how to invoke Gradle"() {
        def settings = settings()
        scenarioFile << """
            cli {
                run-using = cli
            }
            toolingApi {
                run-using = tooling-api
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))

        expect:
        def cli = scenarios[0] as GradleScenarioDefinition
        cli.invoker == Invoker.Cli
        def toolingApi = scenarios[1] as GradleScenarioDefinition
        toolingApi.invoker == Invoker.ToolingApi
    }

    def "scenario can define what state the daemon should be in for each measured build"() {
        def settings = settings(Invoker.ToolingApi)
        scenarioFile << """
            cliCold {
                run-using = cli
                daemon = cold
            }
            none {
                daemon = none
            }
            cold {
                daemon = cold
            }
            warm {
                daemon = warm
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))

        expect:
        def cliCold = scenarios[0] as GradleScenarioDefinition
        cliCold.invoker == Invoker.CliColdDaemon
        def cold = scenarios[1] as GradleScenarioDefinition
        cold.invoker == Invoker.ToolingApiColdDaemon
        def none = scenarios[2] as GradleScenarioDefinition
        none.invoker == Invoker.CliNoDaemon
        def warm = scenarios[3] as GradleScenarioDefinition
        warm.invoker == Invoker.ToolingApi
    }

    def "uses warm-up and iteration counts based on command-line options when Gradle invocation defined by scenario"() {
        def benchmarkSettings = settings(Invoker.ToolingApi, true, 123, 509)
        def profileSettings = settings(Invoker.ToolingApi, false, 25, 44)

        scenarioFile << """
            default {
                run-using = tooling-api
                daemon = warm
            }
        """
        def benchmarkScenarios = loadScenarios(scenarioFile, benchmarkSettings, Mock(GradleBuildConfigurationReader))
        def profileScenarios = loadScenarios(scenarioFile, profileSettings, Mock(GradleBuildConfigurationReader))

        expect:
        def benchmarkScenario = benchmarkScenarios[0] as GradleScenarioDefinition
        benchmarkScenario.warmUpCount == 123
        benchmarkScenario.buildCount == 509

        def profileScenario = profileScenarios[0] as GradleScenarioDefinition
        profileScenario.warmUpCount == 25
        profileScenario.buildCount == 44
    }

    def "uses warm-up and iteration counts based on Gradle invocation defined by scenario"() {
        def benchmarkSettings = settings()
        def profileSettings = settings(Invoker.ToolingApi, false)

        scenarioFile << """
            default {
                run-using = ${runUsing}
                daemon = ${daemon}
            }
        """
        def benchmarkScenarios = loadScenarios(scenarioFile, benchmarkSettings, Mock(GradleBuildConfigurationReader))
        def profileScenarios = loadScenarios(scenarioFile, profileSettings, Mock(GradleBuildConfigurationReader))

        expect:
        def benchmarkScenario = benchmarkScenarios[0] as GradleScenarioDefinition
        benchmarkScenario.warmUpCount == warmups
        benchmarkScenario.buildCount == 10

        def profileScenario = profileScenarios[0] as GradleScenarioDefinition
        profileScenario.warmUpCount == profileWarmups
        profileScenario.buildCount == 1

        where:
        runUsing      | daemon | warmups | profileWarmups
        "tooling-api" | "warm" | 6       | 2
        "tooling-api" | "cold" | 1       | 1
        "cli"         | "warm" | 6       | 2
        "cli"         | "cold" | 1       | 1
        "cli"         | "none" | 1       | 1
    }

    def "can load tooling model scenarios"() {
        def settings = settings()

        scenarioFile << """
            one {
                model = "${IdeaProject.class.name}"
            }
            two {
                model = "${IdeaProject.class.name}"
                tasks = ["help"]
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["one", "two"]
        def scenario1 = scenarios[0] as GradleScenarioDefinition
        scenario1.action instanceof LoadToolingModelAction
        scenario1.action.toolingModel == IdeaProject
        scenario1.action.tasks == []
        def scenario2 = scenarios[1] as GradleScenarioDefinition
        scenario2.action instanceof LoadToolingModelAction
        scenario2.action.toolingModel == IdeaProject
        scenario2.action.tasks == ["help"]
    }

    def "can load single Android studio sync scenario"() {
        def settings = settings()

        scenarioFile << """
            default {
                android-studio-sync { }               
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["default"]
        def scenarioDefinition = scenarios[0] as GradleScenarioDefinition
        scenarioDefinition.action instanceof AndroidStudioSyncAction
    }

    def "loads default scenarios only"() {
        def settings = settings()

        scenarioFile << """
            default-scenarios = ["alma", "bela"]

            default {
                tasks = ["help"]
            }

            alma {
                tasks = ["alma"]
            }

            bela {
                tasks = ["bela"]
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["alma", "bela"]
        (scenarios[0] as GradleScenarioDefinition).action.tasks == ["alma"]
        (scenarios[1] as GradleScenarioDefinition).action.tasks == ["bela"]
    }

    def "loads included config"() {
        def settings = settings()

        def otherConf = tmpDir.newFile("other.conf")
        otherConf << """
            default-scenarios = ["alma"]
            alma {
                tasks = ["alma"]
            }
        """

        scenarioFile << """
            bela {
                tasks = ["bela"]
            }
            
            include file("${otherConf.absolutePath.replace((char) '\\', (char) '/')}")
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["alma"]
        (scenarios[0] as GradleScenarioDefinition).action.tasks == ["alma"]
    }

    def "can load Bazel scenario"() {
        def settings = settings(Invoker.Bazel)

        scenarioFile << """
            default {
                bazel {
                    targets = ["help"]
                }
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["default"]
        (scenarios[0] as BazelScenarioDefinition).targets == ["help"]
    }

    def "can load Buck scenario"() {
        def settings = settings(Invoker.Buck)

        scenarioFile << """
            default {
                buck {
                    targets = ["help"]
                }
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["default"]
        (scenarios[0] as BuckScenarioDefinition).targets == ["help"]
    }

    def "can load Maven scenario"() {
        def settings = settings(Invoker.Maven)

        scenarioFile << """
            default {
                maven {
                    targets = ["help"]
                }
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))
        expect:
        scenarios*.name == ["default"]
        (scenarios[0] as MavenScenarioDefinition).targets == ["help"]
    }

    def "can load scenario with multiple files for a single mutation"() {
        def settings = settings()

        def fileForMutation1 = new File(projectDir, "fileForMutation1.java")
        def fileForMutation2 = new File(projectDir, "fileForMutation2.kt")

        fileForMutation1.createNewFile()
        fileForMutation2.createNewFile()

        scenarioFile << """
            default {
                tasks = ["help"]
                
                apply-abi-change-to = ["${fileForMutation1.name}", "${fileForMutation2.name}"]
            }
        """
        def scenarios = loadScenarios(scenarioFile, settings, Mock(GradleBuildConfigurationReader))

        expect:
        scenarios*.name == ["default"]
    }
}
