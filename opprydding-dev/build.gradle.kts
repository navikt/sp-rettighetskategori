plugins {
    id("no.nav.sykepenger.deployable")
}

sykepengerDeployable {
    mainClass = "no.nav.helse.sykepenger.rettighetskategori.opprydding_dev.AppKt"
    imageName = "${rootProject.name}-opprydding-dev"
}

dependencies {
    implementation(libs.hikaricp)
    implementation(libs.kotliquery)
    implementation(libs.postgresql)
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.rapids.and.rivers)
    implementation(libs.sykepengerLibs.logging)

    testImplementation(project(":migreringer"))
    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.flyway.database.postgresql)
}
