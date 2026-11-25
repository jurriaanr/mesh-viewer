# Recipes are plain commands only: on Windows make runs them through cmd.exe.
# Anything with logic lives in scripts/ (pwsh 7, runs on Windows and Linux).

ifeq ($(OS),Windows_NT)
GRADLE := gradlew.bat
else
GRADLE := ./gradlew
endif

.PHONY: help run app release release-minor release-major release-dry

help: ## list the targets
	@pwsh -NoProfile -Command "Select-String -Path Makefile -Pattern '^([a-z-]+):.*?## (.*)$$' | ForEach-Object { '  {0,-15} {1}' -f $$_.Matches[0].Groups[1].Value, $$_.Matches[0].Groups[2].Value }"

run: ## run the app from source
	$(GRADLE) run

app: ## build the packaged app without installing (build/compose/binaries/main/app)
	$(GRADLE) createDistributable

release: ## bump the patch version (1.0.0 -> 1.0.1), build the installer, commit and tag
	pwsh -NoProfile -File scripts/release.ps1 -Bump patch

release-minor: ## bump the minor version (1.0.1 -> 1.1.0), build, commit and tag
	pwsh -NoProfile -File scripts/release.ps1 -Bump minor

release-major: ## bump the major version (1.1.0 -> 2.0.0), build, commit and tag
	pwsh -NoProfile -File scripts/release.ps1 -Bump major

release-dry: ## show what `make release` would do, without changing anything
	pwsh -NoProfile -File scripts/release.ps1 -Bump patch -DryRun
