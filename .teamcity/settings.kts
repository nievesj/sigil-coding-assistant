import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.script
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
        param("env.CREATE_RELEASE", "%CREATE_RELEASE%")
        param("env.LLM_API_KEY", "%LLM_API_KEY%")
        param("env.LLM_API_URL", "%LLM_API_URL%")
        param("LLM_API_URL", "https://ollama.com/v1/chat/completions")
        param("CREATE_RELEASE", "true")
    }

    vcs {
        root(SigilGitSSH)
    }

    steps {
        script {
            name = "Build and Package"
            scriptContent = """
                powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File .teamcity/build.ps1 -BuildNumber "%build.number%"
            """.trimIndent()
        }
    }

    triggers {
        vcs {
            branchFilter = "+:refs/heads/main"
            enableQueueOptimization = false
        }
    }
})

object SigilGitSSH : GitVcsRoot({
    name = "SigilGitSSH"
    url = "git@github.com:nievesj/sigil-coding-assistant.git"
    branch = "refs/heads/main"
    authMethod = uploadedKey {
        userName = "git"
        uploadedKey = "GitHubPerforceSyncKey"
    }
})