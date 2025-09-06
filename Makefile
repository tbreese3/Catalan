# --------- Catalan Makefile: just run the batch script -----------
EXE ?= Catalan.exe                 # OpenBench passes EXE=Catalan-<sha>[.exe]

.PHONY: all clean

all:
	build.bat "$(EXE)"

clean:
	gradlew.bat --no-daemon clean
