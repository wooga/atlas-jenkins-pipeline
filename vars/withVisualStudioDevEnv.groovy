def call(Map config = [:], block) {
    def out = powershell(
        returnStdout: true,
        label: "setup vs dev environment",
        script: '''
            $vswhereCmd = Get-Command vswhere -ErrorAction SilentlyContinue
            if (-not $vswhereCmd) {
                Write-Error "vswhere not found."
                exit 1
            }

            $vsDevCmd = vswhere -all -latest -products * `
                -requires Microsoft.VisualStudio.Workload.VCTools `
                -find **/VsDevCmd.bat |
                Select-Object -First 1

            if (-not $vsDevCmd) {
                Write-Error "VsDevCmd.bat not found (Visual Studio C++ tools missing?)"
                exit 1
            }
            cmd /c "`"$vsDevCmd`" -arch=amd64 >nul && set"
        '''.stripIndent().trim()
    ).trim()

    // Convert the output to KEY=VALUE format for withEnv
    def pairs = out.readLines()
            .findAll { it.contains('=') }
            .collect { it }

    withEnv(pairs) {
        block()
    }
}
