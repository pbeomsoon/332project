name := "distsort"
version := "0.1.0"
scalaVersion := "2.13.12"

// gRPC & Protobuf
lazy val grpcVersion = "1.54.0"
lazy val scalapbVersion = "0.11.13"

libraryDependencies ++= Seq(
  // gRPC
  "io.grpc" % "grpc-netty" % grpcVersion,
  "io.grpc" % "grpc-protobuf" % grpcVersion,
  "io.grpc" % "grpc-stub" % grpcVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,

  // Logging (Java 8 compatible version)
  "ch.qos.logback" % "logback-classic" % "1.2.12",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // JSON serialization for checkpointing
  "com.google.code.gson" % "gson" % "2.10.1",

  // Testing
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test,
  "io.grpc" % "grpc-testing" % grpcVersion % Test,
  "commons-io" % "commons-io" % "2.11.0" % Test
)

// ScalaPB settings for Protocol Buffers
Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

// Test settings
Test / parallelExecution := false
Test / fork := true
Test / testOptions += Tests.Argument("-oD")

// Coverage (commented out for now - dependency issue)
// coverageEnabled := true
// coverageMinimumStmtTotal := 80
