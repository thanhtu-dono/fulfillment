# Order Fulfillment Engine

Java 17+ console application for the multi-center fulfillment assignment.

## Current status

The project currently contains the domain model, OFP protocol primitives, inventory repository, reservation service, backorder service, audit trail, console entry point, sample input files and a Java Core stress-test harness.

## Compile and run

```powershell
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
New-Item -ItemType Directory out | Out-Null
javac --release 17 -d out (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName)
java -cp out com.example.fulfillment.Main
```

The PDF assignment remains local and is excluded by `.gitignore`.
