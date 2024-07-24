properties([
        parameters([
                choice(choices: ['Major', 'Minor', 'Patch'], description: 'Select the type of release application for FrontEnd.', name: 'RELEASE_SELECT'),
        ])
])

@Library('mangampire-shared-library')

def service = new MangampireService().call()
def module = "mangampire-frontend-service"
def newVersion

pipeline {
    agent any
    environment {
        GITHUB_USERNAME = "${env.GITHUB_USERNAME}"
        GITHUB_EMAIL = "${env.GITHUB_EMAIL}"
    }
    stages {
        stage('Checkout Repository') {
            steps {
                script {
                    checkout([$class           : 'GitSCM',
                              branches         : [[name: "origin/develop"]],
                              userRemoteConfigs: [[url: 'https://github.com/DannyBatchRun/mangampire-frontend-repository.git', credentialsId: 'mangampire-repository-credentials']]])
                    sh("git config --global user.email \"${GITHUB_EMAIL}\"")
                    sh("git config --global user.name \"${GITHUB_USERNAME}\"")
                    sh("set +x; git remote set-url origin https://github.com/DannyBatchRun/mangampire-frontend-repository.git")
                    sh("git checkout develop")
                }
            }
        }
        stage('Update App Version') {
            steps {
                script {
                    sh("mvn -v")
                    println "Updating version frontend to ${params.RELEASE_SELECT} version."
                    newVersion = service.updateApplicationVersionFrontend("${params.RELEASE_SELECT}")
                    currentBuild.displayName = "#${currentBuild.number} - Version ${newVersion}"
                    currentBuild.description = "Build n°#${currentBuild.number}"
                    sleep 20
                }
            }
        }
        stage('Build Packages') {
            steps {
                script {
                    println "Package and build will start."
                    sh("mvn clean install compile jib:dockerBuild -DskipTests")
                    service.pushDockerImage(null,"${module}")
                }
            }
        }
        stage('Clean and Push') {
            steps {
                script {
                    println "Clean local and push to repository..."
                    sleep 20
                    service.deleteLocalDockerImages("${module}")
                    withCredentials([string(credentialsId: 'github-pat-secret', variable: 'GIT_TOKEN')]) {
                        service.updateDevelopBranchFrontend("${newVersion}","${GIT_TOKEN}")
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                println "Pipeline completed successfully"
            }
            cleanWs()
        }
        failure {
            println "Oops! Something went wrong. Try to launch pipeline again."
            cleanWs()
        }
    }
}











