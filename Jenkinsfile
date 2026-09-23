pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    // Addon-facing API closure published for out-of-repo addon authors.
    // Keep in sync with `publishedModulePaths` in the root build.gradle and the
    // `install:` block in jitpack.yml.
    PUBLISH_TASKS = ':commands-api:publishToMavenLocal :yaml-api:publishToMavenLocal ' +
                    ':metrics-api:publishToMavenLocal :maps-api:publishToMavenLocal ' +
                    ':effects-api:publishToMavenLocal :rtp-proxy:rtp-proxy-common:publishToMavenLocal ' +
                    ':rtp-api:publishToMavenLocal :rtp-core:publishToMavenLocal'
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

    // Publish the addon-facing API closure (rtp-api/rtp-core + their
    // project-dependency closure) on every push or merge to the V3 line.
    // JitPack serves these branch snapshots on demand as
    // `com.github.DailyStruggle.RTP:<module>:V3-SNAPSHOT`; this stage both
    // validates the publication graph and primes the local Maven repo. A
    // remote-repo upload (Maven Central snapshots / GitHub Packages) can be
    // swapped in by replacing the publish tasks with the credentialed target.
    stage('Publish API Artifacts') {
      when {
        anyOf {
          branch 'V3'
          branch 'V3-beta'
        }
      }
      steps {
        sh "./gradlew --no-daemon ${PUBLISH_TASKS}"
      }
    }

    stage('Archive Artifacts') {
      when {
        anyOf {
          branch 'main'
          branch 'V3'
        }
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
