pipeline {
  agent any
  stages {
    stage( 'Build' ) {
      steps {
        echo 'Building with Gradle'
        sh 'chmod +x gradlew'
        sh './gradlew build shadowJar'
      }
    }
    stage( 'Archive Artifacts' ) {
      steps {
        archiveArtifacts artifacts: 'build/libs/*.jar, addons/*/build/libs/*.jar', fingerprint: true
      }
    }
  }
}