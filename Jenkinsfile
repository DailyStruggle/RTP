pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  stages {
    stage('Traceability Check') {
      steps {
        sh 'chmod +x check_traceability.sh'
        sh './check_traceability.sh'
      }
    }

    stage('Code Style') {
      steps {
        sh 'chmod +x gradlew'
        sh './gradlew spotlessCheck'
      }
    }

    stage('Build') {
      steps {
        sh './gradlew assemble shadowJar'
      }
    }

    stage('Test') {
      steps {
        sh './gradlew test'
      }
      post {
        always {
          junit '**/build/test-results/test/TEST-*.xml'
          jacoco(
            execPattern: '**/build/jacoco/*.exec',
            classPattern: '**/build/classes/java/main',
            sourcePattern: '**/src/main/java'
          )
        }
      }
    }

    stage('Archive Artifacts') {
      when {
        branch 'main'
      }
      steps {
        archiveArtifacts artifacts: 'build/libs/*.jar, addons/*/build/libs/*.jar',
                         fingerprint: true
      }
    }
  }

  post {
    always {
      cleanWs()
    }
    failure {
      echo "Pipeline failed on branch ${env.BRANCH_NAME} — check stage logs above."
    }
    success {
      echo "Pipeline passed. Artifacts archived for branch ${env.BRANCH_NAME}."
    }
  }
}
