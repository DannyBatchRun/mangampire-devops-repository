properties([
        parameters([
                gitParameter(branch: '', branchFilter: 'origin1/(.*)', defaultValue: 'origin/develop',
                                         description: '''Select branch name for backend-repository.\nWARNING : If you select release branch, it will download artifacts\nfrom mangampire-build-release-application pipeline.''',
                        name: 'BRANCH_NAME', quickFilterEnabled: true, selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*',
                        type: 'GitParameterDefinition', useRepository: 'https://github.com/DannyBatchRun/mangampire-backend-repository.git'
                )]
        )
])

@Library('mangampire-shared-library')

import jenkins.model.Jenkins

def service = new MangampireService().call()
def buildName
def jarFile

pipeline {
    agent any
    environment {
        GITHUB_USERNAME = "${env.GITHUB_USERNAME}"
        GITHUB_EMAIL = "${env.GITHUB_EMAIL}"
        DATABASE_STOREHOUSE_URL = "${env.DATABASE_STOREHOUSE_URL}"
        DATABASE_TRANSACTION_URL = "${env.TRANSACTION_STOREHOUSE_URL}"
        SHOPPINGCART_DATABASE_URL = "${env.SHOPPINGCART_DATABASE_URL}"
        DATABASE_PASSWORD = "${env.DATABASE_PASSWORD}"
        REGION = "${env.REGION}"
        USERNAME_AWS = "${env.USERNAME_AWS}"
        AWS_ACCOUNT = "${env.AWS_ACCOUNT}"
    }
    stages {
        stage('Checkout Repository') {
            steps {
                script {
                    checkout([$class           : 'GitSCM',
                              branches         : [[name: "origin/${params.BRANCH_NAME}"]],
                              userRemoteConfigs: [[url: 'https://github.com/DannyBatchRun/mangampire-backend-repository.git', credentialsId: 'mangampire-repository-credentials']]])
                    sh("git config --global user.email \"${GITHUB_EMAIL}\"")
                    sh("git config --global user.name \"${GITHUB_USERNAME}\"")
                    sh("set +x; git remote set-url origin https://github.com/DannyBatchRun/mangampire-backend-repository.git")
                    sh("git checkout ${params.BRANCH_NAME}")
                }
            }
        }
        stage('Build or Download Packages') {
            steps {
                script {
                    if (BRANCH_NAME ==~ /release\/\d+\.\d+\.\d+/) {
                        def keyword = BRANCH_NAME
                        def jobName = "mangampire-release-build-application"
                        Jenkins.instance.with {
                            def job = it.getItem(jobName)
                            if (job) {
                                def builds = job.builds.findAll { it.displayName.contains(keyword) }
                                if(builds.size() > 0) {
                                    buildName = builds[0].displayName
                                    println("Name of build found : ${buildName}")
                                }
                            }
                        }
                        if(buildName != '') {
                            copyArtifacts filter: 'manga-storehouse/target/*.jar', fingerprintArtifacts: true, projectName: "${jobName}", selector: specific("${buildName}")
                            copyArtifacts filter: 'clients-transaction/target/*.jar', fingerprintArtifacts: true, projectName: "${jobName}", selector: specific("${buildName}")
                            copyArtifacts filter: 'backend-service/target/*.jar', fingerprintArtifacts: true, projectName: "${jobName}", selector: specific("${buildName}")
                            copyArtifacts filter: 'shopping-cart/target/*.jar', fingerprintArtifacts: true, projectName: "${jobName}", selector: specific("${buildName}")
                        }
                    } else {
                        println("Branch selected is not a branch of release. Build in progress...")
                        service.buildMavenServices()
                    }
                }
            }
        }
        stage('Build and Push on ECR') {
            steps {
                script {
                    service.buildDockerImage("manga-storehouse")
                    service.buildDockerImage("clients-transaction")
                    service.buildDockerImage("backend-service")
                    service.buildDockerImage("shopping-cart")
                    service.pushDockerImage("manga-storehouse",service.getJarFile("manga-storehouse"))
                    service.pushDockerImage("clients-transaction",service.getJarFile("clients-transaction"))
                    service.pushDockerImage("backend-service",service.getJarFile("backend-service"))
                    service.pushDockerImage("shopping-cart",service.getJarFile("shopping-cart"))
                }
            }
        }
        stage('CleanUp Local Images') {
            steps {
                script {
                    println "Deleting docker images in local..."
                    sleep 20
                    service.deleteLocalDockerImages(service.getJarFile("manga-storehouse"))
                    service.deleteLocalDockerImages(service.getJarFile("clients-transaction"))
                    service.deleteLocalDockerImages(service.getJarFile("backend-service"))
                    service.deleteLocalDockerImages(service.getJarFile("shopping-cart"))
                }
            }
        }
    }
    post {
        success {
            script {
                println "Images pushed successfully on ECR."
            }
            cleanWs()
        }
        failure {
            println "Oops! Something went wrong. Try to launch pipeline again."
            cleanWs()
        }
    }
}
