import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
   TeamCity build configuration for Sigil.
   This file is stored in the repo under .teamcity/ and version-controlled.
   Secrets (LLM_API_KEY) are stored as secure params in TeamCity and
   referenced by placeholder here — the actual value is never committed.
*/

version = "2026.1"

project {

    vcsRoot(SigilGitSSH)

    buildType(Build)
}

object Build : BuildType({
    name = "Build"

    buildNumberPattern = "1.0.%build.counter%"

    params {
        param("env.BUILD_COUNTER", "%build.counter%")
        param("env.CREATE_RELEASE", "%CREATE_RELEASE%")
        param("env.LLM_API_KEY", "%LLM_API_KEY%")
        param("env.LLM_API_URL", "%LLM_API_URL%")
        param("LLM_API_URL", "https://ollama.com/v1/chat/completions")
        param("CREATE_RELEASE", "true")
        // Marketplace publishing — values stored as secure params in TeamCity
        param("env.PUBLISH_TOKEN", "%PUBLISH_TOKEN%")
        param("env.CERTIFICATE_CHAIN", "%CERTIFICATE_CHAIN%")
        param("env.PRIVATE_KEY", "%PRIVATE_KEY%")
        param("env.PRIVATE_KEY_PASSWORD", "%PRIVATE_KEY_PASSWORD%")
    }

    vcs {
        root(SigilGitSSH)
    }

    steps {
        powerShell {
            name = "Build and Package"
            scriptMode = file {
                path = ".teamcity/build.ps1"
            }
            scriptArgs = "-buildCounter %build.counter%"
        }
    }

    triggers {
        vcs {
            branchFilter = "+:refs/heads/*"
            enableQueueOptimization = false
        }
    }
})

object SigilGitSSH : GitVcsRoot({
    name = "SigilGitSSH"
    url = "git@github.com:nievesj/sigil-coding-assistant.git"
    branch = "refs/heads/main"
    branchSpec = "+:refs/heads/*"
    authMethod = uploadedKey {
        userName = "git"
        uploadedKey = "GitHubPerforceSyncKey"
    }
})