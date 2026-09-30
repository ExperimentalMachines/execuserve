/*
 * The server's decisions: what runs, in what order, on which model, and when it stops.
 * Nothing here knows about HTTP or about a phone; both reach it through the types it
 * declares (requests and events up, the runtime SPI and an environment reading down).
 */
plugins {
    id("execuserve.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:prompt"))
        }
        jvmTest.dependencies {
            implementation(project(":jvm:testing"))
        }
    }
}
