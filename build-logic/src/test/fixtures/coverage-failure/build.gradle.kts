plugins {
    java
    id("dstu.coverage")
}

dstuCoverage {
    minimum.set("1.0".toBigDecimal())
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
