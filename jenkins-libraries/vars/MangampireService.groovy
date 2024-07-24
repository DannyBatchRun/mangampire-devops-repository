def call() {
    println "*** Mangampire Service initialized ***"
    return this;
}

def updateApplicationVersion(def pathSelect, def releaseSelect) {
    def newVersion = ''
    dir("${pathSelect}") {
        def oldVersion = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true).trim()
        println "Actual version is ${oldVersion}. Updating..."
        def versionParts = oldVersion.tokenize('.')
        switch (releaseSelect) {
            case "Major":
                versionParts[0] = versionParts[0].toInteger() + 1
                versionParts[1] = '0'
                versionParts[2] = '0'
                break
            case "Minor":
                versionParts[1] = versionParts[1].toInteger() + 1
                versionParts[2] = '0'
                break
            case "Patch":
                versionParts[2] = versionParts[2].toInteger() + 1
                break
        }
        newVersion = versionParts.join('.')
        sh("""mvn versions:set -DnewVersion=${newVersion} versions:commit""")
    }
    return newVersion
}


def getJarFile(def selectedService) {
    def nameJar = ""
    switch(selectedService) {
        case "manga-storehouse":
            nameJar = "mangampire-storehouse-service"
            break
        case "clients-transaction":
            nameJar = "mangampire-transaction"
            break
        case "backend-service":
            nameJar = "mangampire-backend-service"
            break
    }
    return nameJar
}

def buildMavenServices() {
    def services = ['manga-storehouse', 'clients-transaction', 'backend-service']
    services.each { def service ->
        dir(service) {
            sh("mvn clean install -DskipTests")
        }
    }
}

def updateDevelopBranch(def newVersion, def newBranch, def backendService, def gitToken, def completeRelease) {
    def repository = "https://${gitToken}@github.com/DannyBatchRun/mangampire-backend-repository.git"
    def commitMessage = (completeRelease) ? "\"Version of all microservices updated to ${newVersion}\"" : "\"Version of ${backendService} updated to ${newVersion}\""
    sh("set +x; git add .")
    sh("set +x; git commit -m ${commitMessage}")
    sh("set +x; git push ${repository} develop")
    if(completeRelease) {
        sh("git tag ${newVersion} && git push ${repository} develop --tags")
    } else {
        sh("git tag ${backendService}/${newVersion} && git push ${repository} develop --tags")
    }
}

def createANewBranch(def newVersion, def newBranch, def backendService, def gitToken, def completeRelease) {
    sh("git checkout -b ${newBranch}")
    sh("set +x; git push -u https://${gitToken}@github.com/DannyBatchRun/mangampire-backend-repository.git ${newBranch}")
}

def buildDockerImage(def microservice) {
    dir("${microservice}") {
        sh("mvn compile jib:dockerBuild")
    }
}

def pushDockerImage(def microservice, def jarFile) {
    dir("${microservice}/target") {
        def command = "find . -name '${jarFile}-*.jar'"
        def output = sh(script: command, returnStdout: true).trim()
        def version = output.replaceAll(/.*-(\d+\.\d+\.\d+)\.jar/, '$1')
        if (version == null) {
            throw new RuntimeException("Unable to extract version from file name: ${fileName}")
        }
        sh("aws ecr get-login-password --region ${REGION} | docker login --username ${USERNAME_AWS} --password-stdin ${AWS_ACCOUNT}.dkr.ecr.us-east-1.amazonaws.com")
        sh("docker tag ${jarFile}:${version} ${AWS_ACCOUNT}.dkr.ecr.us-east-1.amazonaws.com/${jarFile}:${version}")
        sh("docker push ${AWS_ACCOUNT}.dkr.ecr.us-east-1.amazonaws.com/${jarFile}:${version}")
    }
}

def deleteLocalDockerImages(def imageFile) {
    sh("docker rmi -f \$(docker images -q ${imageFile})")
}







