plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.rettighetskategori.AppKt"
}

dependencies {
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.logging)
    implementation(libs.flyway.database.postgresql)
    implementation(project(":migreringer"))

    testImplementation(libs.kotliquery)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgres)
}
