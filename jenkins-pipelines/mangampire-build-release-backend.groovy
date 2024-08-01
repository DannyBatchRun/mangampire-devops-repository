properties([
        parameters([
                choice(choices: ['manga-storehouse', 'clients-service', 'backend-service','shopping-cart','transaction-service'], description: 'Choose the service you want to build.', name: 'BACKEND_SERVICE'),
                choice(choices: ['Major', 'Minor', 'Patch'], description: 'Select the type of release application.', name: 'RELEASE_SELECT'),
                booleanParam(description: 'Select this checkbox if you want to update all microservices.', name: 'COMPLETE_RELEASE')
        ])
])

@Library('mangampire-shared-library')

def service = new MangampireService().call()
def newVersion
def maxVersion
def newBranch
def incrementedBranch

pipeline {
    agent any
    environment {
        GITHUB_USERNAME = "${env.GITHUB_USERNAME}"
        GITHUB_EMAIL = "${env.GITHUB_EMAIL}"
        DATABASE_STOREHOUSE_URL = "${env.DATABASE_STOREHOUSE_URL}"
        DATABASE_CLIENT_URL = "${env.DATABASE_CLIENT_URL}"
        DATABASE_TRANSACTION_URL = "${env.DATABASE_TRANSACTION_URL}"
        SHOPPINGCART_DATABASE_URL = "${env.SHOPPINGCART_DATABASE_URL}"
        DATABASE_PASSWORD = "${env.DATABASE_PASSWORD}"
    }
    stages {
        stage('Checkout Repository') {
            steps {
                script {
                    checkout([$class           : 'GitSCM',
                              branches         : [[name: "origin/develop"]],
                              userRemoteConfigs: [[url: 'https://github.com/DannyBatchRun/mangampire-backend-repository.git', credentialsId: 'mangampire-repository-credentials']]])
                    sh("git config --global user.email \"${GITHUB_EMAIL}\"")
                    sh("git config --global user.name \"${GITHUB_USERNAME}\"")
                    sh("set +x; git remote set-url origin https://github.com/DannyBatchRun/mangampire-backend-repository.git")
                    sh("git checkout develop")
                    COMPLETE_RELEASE = COMPLETE_RELEASE.toBoolean()
                }
            }
        }
        stage('Update App Version') {
            steps {
                script {
                    sh("mvn -v")
                    if(COMPLETE_RELEASE) {
                        currentBuild.description = "Build n°#${currentBuild.number}"
                        println "Updating ALL versions for microservices to ${params.RELEASE_SELECT} release."
                        def versions = ['manga-storehouse', 'clients-service','backend-service','shopping-cart','transaction-service'].collect {
                            service.updateApplicationVersion(it, "${params.RELEASE_SELECT}")
                        }
                        maxVersion = versions.max()
                        newBranch = "release/${maxVersion}"
                    } else {
                        println "Building ${params.BACKEND_SERVICE} with the new version ${newVersion}"
                        newVersion = service.updateApplicationVersion("${params.BACKEND_SERVICE}", "${params.RELEASE_SELECT}")
                        newBranch = "release/${params.BACKEND_SERVICE}/${newVersion}"
                    }
                    currentBuild.displayName = "#${currentBuild.number} - ${newBranch}"
                    sleep 20
                }
            }
        }
        stage('Build Packages') {
            steps {
                script {
                    println "Package Mangampire services in progress..."
                    service.buildMavenServices()
                }
            }
        }
        stage('Push Release on Repository') {
            steps {
                script {
                    println "Pushing a new branch ${newBranch}"
                    withCredentials([string(credentialsId: 'github-pat-secret', variable: 'GIT_TOKEN')]) {
                        println "Update develop branch in progress..."
                        if(COMPLETE_RELEASE) {
                            service.updateDevelopBranch("${maxVersion}","${newBranch}","${params.BACKEND_SERVICE}","${GIT_TOKEN}",COMPLETE_RELEASE)
                            def baseBranchName = newBranch.tokenize('/')[0..1].join('/')
                            def result = sh(script: "git ls-remote --heads origin ${baseBranchName}*", returnStdout: true).trim()
                            def branchExists = result ? true : false
                            if (branchExists) {
                                def version = newBranch.split('/')[1]
                                def patch = version.split('\\.')[2].toInteger() + 1
                                def incrementedVersion = version.replaceAll(/(\d+)$/, patch.toString())
                                incrementedBranch = newBranch.replaceAll(version, incrementedVersion)
                                currentBuild.displayName = "#${currentBuild.number} - ${incrementedBranch}"
                                service.createANewBranch("${incrementedVersion}","${incrementedBranch}","${params.BACKEND_SERVICE}","${GIT_TOKEN}",COMPLETE_RELEASE)
                            } else if (!branchExists) {
                                service.createANewBranch("${maxVersion}","${newBranch}","${params.BACKEND_SERVICE}","${GIT_TOKEN}",COMPLETE_RELEASE)
                            }
                        } else {
                            service.updateDevelopBranch("${newVersion}","${newBranch}","${params.BACKEND_SERVICE}","${GIT_TOKEN}",COMPLETE_RELEASE)
                            service.createANewBranch("${newVersion}","${newBranch}","${params.BACKEND_SERVICE}","${GIT_TOKEN}",COMPLETE_RELEASE)
                        }
                    }
                }
            }
        }
        stage('Archive Artifacts') {
            steps {
                script {
                    println "Archiving JAR files..."
                    archiveArtifacts artifacts: "manga-storehouse/target/*.jar", followSymlinks: false, onlyIfSuccessful: true
                    archiveArtifacts artifacts: "clients-service/target/*.jar", followSymlinks: false, onlyIfSuccessful: true
                    archiveArtifacts artifacts: "transaction-service/target/*.jar", followSymlinks: false, onlyIfSuccessful: true
                    archiveArtifacts artifacts: "backend-service/target/*.jar", followSymlinks: false, onlyIfSuccessful: true
                    archiveArtifacts artifacts: "shopping-cart/target/*.jar", followSymlinks: false, onlyIfSuccessful: true
                }
            }
        }
    }
    post {
        success {
            script {
                if(!COMPLETE_RELEASE) {
                    println "${params.BACKEND_SERVICE} updated to version ${newVersion}"
                } else {
                    println "All services are updated to ${params.RELEASE_SELECT} release."
                }
            }
            cleanWs()
        }
        failure {
            println "Oops! Something went wrong. Try to launch pipeline again."
            cleanWs()
        }
    }
}











