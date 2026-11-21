package process

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ProcessCPUHistory stores previous CPU stats for calculating delta CPU %
type ProcessCPUHistory struct {
	TotalTime uint64
	Timestamp time.Time
}

// Detector handles scanning and inspecting Java processes
type Detector struct {
	cpuHistory map[int]ProcessCPUHistory
	clkTck     float64
	selfPID    int
}

func NewDetector() *Detector {
	return &Detector{
		cpuHistory: make(map[int]ProcessCPUHistory),
		clkTck:     100.0, // Standard Linux USER_HZ
		selfPID:    os.Getpid(),
	}
}

// Scan processes in /proc and via jps, returning all Java applications
func (d *Detector) Scan() ([]JavaApp, error) {
	now := time.Now()

	// 1. Get JDK jps map: PID -> class/jar name
	jpsMap := d.scanJps()

	// 2. Read /proc directory
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, err
	}

	var apps []JavaApp
	activePIDs := make(map[int]bool)

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue // Not a PID folder
		}

		// Never detect self (javaview3d)
		if pid == d.selfPID {
			continue
		}

		jpsInfo, inJps := jpsMap[pid]
		app, isJava := d.inspectPID(pid, inJps, jpsInfo, now)
		if isJava && app != nil {
			activePIDs[pid] = true
			apps = append(apps, *app)
		}
	}

	// Clean up stale CPU history
	for pid := range d.cpuHistory {
		if !activePIDs[pid] {
			delete(d.cpuHistory, pid)
		}
	}

	return apps, nil
}

// scanJps invokes `jps -l` to get an authoritative list of Java PIDs
func (d *Detector) scanJps() map[int]string {
	result := make(map[int]string)

	jpsBin := findJpsBinary()
	if jpsBin == "" {
		return result
	}

	cmd := exec.Command(jpsBin, "-l")
	out, err := cmd.Output()
	if err != nil {
		return result
	}

	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 1 {
			pid, err := strconv.Atoi(fields[0])
			if err != nil || pid == d.selfPID {
				continue
			}
			label := ""
			if len(fields) >= 2 {
				label = fields[1]
			}
			// Exclude jps itself
			if strings.Contains(label, "sun.tools.jps.Jps") {
				continue
			}
			result[pid] = label
		}
	}

	return result
}

// inspectPID checks if a given PID is a Java process and parses its properties
func (d *Detector) inspectPID(pid int, inJps bool, jpsInfo string, now time.Time) (*JavaApp, bool) {
	pidStr := strconv.Itoa(pid)
	procDir := filepath.Join("/proc", pidStr)

	// Check executable symlink
	exePath, _ := os.Readlink(filepath.Join(procDir, "exe"))
	baseExe := filepath.Base(exePath)

	commBytes, _ := os.ReadFile(filepath.Join(procDir, "comm"))
	comm := strings.TrimSpace(string(commBytes))

	// Explicitly ignore non-service tools
	if comm == "jps" || baseExe == "jps" || comm == "javac" || baseExe == "javac" {
		return nil, false
	}

	// Check if this is a Java VM
	isJava := inJps || comm == "java" || baseExe == "java" || strings.HasSuffix(exePath, "/bin/java")

	// Read cmdline
	cmdlineRaw, _ := os.ReadFile(filepath.Join(procDir, "cmdline"))
	var args []string
	if len(cmdlineRaw) > 0 {
		rawParts := bytes.Split(cmdlineRaw, []byte{0})
		for _, p := range rawParts {
			if len(p) > 0 {
				args = append(args, string(p))
			}
		}
	}

	// If not yet confirmed as Java, check first cmdline arg
	if !isJava && len(args) > 0 {
		firstBase := filepath.Base(args[0])
		if firstBase == "java" || strings.HasSuffix(args[0], "/bin/java") {
			isJava = true
		}
	}

	if !isJava {
		return nil, false
	}

	// Exclude if it's jps or javaview3d in args
	if len(args) > 0 {
		arg0 := filepath.Base(args[0])
		if arg0 == "javaview3d" || strings.Contains(args[0], "javaview3d") {
			return nil, false
		}
		for _, a := range args {
			if strings.Contains(a, "sun.tools.jps.Jps") {
				return nil, false
			}
		}
	}

	// Read cwd
	cwd, _ := os.Readlink(filepath.Join(procDir, "cwd"))
	if cwd == "" {
		cwd = os.Getenv("HOME")
	}

	// Parse arguments, VM flags, main class, jar
	vmArgs, appArgs, mainClass, jarPath := parseJavaArgs(args)

	// If mainClass or jar was not found from cmdline, use jpsInfo
	if mainClass == "" && jarPath == "" && jpsInfo != "" {
		if strings.HasSuffix(jpsInfo, ".jar") {
			jarPath = jpsInfo
		} else {
			mainClass = jpsInfo
		}
	}

	// Read status (VmRSS, VmSize, Threads)
	rss, vms, threads := readProcStatus(procDir)

	// Calculate CPU %
	cpuPct := d.calculateCPU(pid, procDir, now)

	// Determine clean display name
	name := formatAppName(mainClass, jarPath, cwd, args)

	// Determine if this is a demo app created by our tool
	isDemo := false
	for _, arg := range args {
		if strings.Contains(arg, "Java3DDemoApp") || strings.Contains(arg, "DemoWorker") {
			isDemo = true
			break
		}
	}

	// Generate stable ID
	appID := generateAppID(cwd, mainClass, jarPath, args)

	// Memory formatted
	memStr := formatBytes(rss)

	if exePath == "" {
		exePath = findJavaBinary()
	}

	app := &JavaApp{
		ID:          appID,
		PID:         pid,
		Name:        name,
		MainClass:   mainClass,
		JarPath:     jarPath,
		Executable:  exePath,
		Args:        args,
		VMArgs:      vmArgs,
		AppArgs:     appArgs,
		Cwd:         cwd,
		Status:      "RUNNING",
		StartTime:   now,
		UptimeSec:   readUptime(pid),
		MemoryRSS:   rss,
		MemoryVMS:   vms,
		MemoryStr:   memStr,
		CPUPercent:  cpuPct,
		Threads:     threads,
		IsDemo:      isDemo,
		CanRestart:  true,
	}

	return app, true
}

func parseJavaArgs(args []string) (vmArgs []string, appArgs []string, mainClass string, jarPath string) {
	if len(args) <= 1 {
		return nil, nil, "", ""
	}

	parsingVM := true
	for i := 1; i < len(args); i++ {
		arg := args[i]

		if parsingVM {
			if arg == "-jar" {
				if i+1 < len(args) {
					jarPath = args[i+1]
					i++
				}
				parsingVM = false
				continue
			}

			if arg == "-m" || arg == "--module" {
				if i+1 < len(args) {
					mainClass = args[i+1]
					i++
				}
				parsingVM = false
				continue
			}

			if arg == "-cp" || arg == "-classpath" || arg == "--class-path" {
				vmArgs = append(vmArgs, arg)
				if i+1 < len(args) {
					vmArgs = append(vmArgs, args[i+1])
					i++
				}
				continue
			}

			if strings.HasPrefix(arg, "-") {
				vmArgs = append(vmArgs, arg)
				continue
			}

			// First non-flag argument when not using -jar is the Main-Class
			mainClass = arg
			parsingVM = false
		} else {
			appArgs = append(appArgs, arg)
		}
	}

	return vmArgs, appArgs, mainClass, jarPath
}

func readProcStatus(procDir string) (rss uint64, vms uint64, threads int) {
	data, err := os.ReadFile(filepath.Join(procDir, "status"))
	if err != nil {
		return 0, 0, 1
	}

	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		key := strings.TrimSuffix(fields[0], ":")
		switch key {
		case "VmRSS":
			val, _ := strconv.ParseUint(fields[1], 10, 64)
			rss = val * 1024 // kB to bytes
		case "VmSize":
			val, _ := strconv.ParseUint(fields[1], 10, 64)
			vms = val * 1024 // kB to bytes
		case "Threads":
			t, _ := strconv.Atoi(fields[1])
			threads = t
		}
	}
	return rss, vms, threads
}

func (d *Detector) calculateCPU(pid int, procDir string, now time.Time) float64 {
	statData, err := os.ReadFile(filepath.Join(procDir, "stat"))
	if err != nil {
		return 0.0
	}

	fields := strings.Fields(string(statData))
	if len(fields) < 15 {
		return 0.0
	}

	utime, err1 := strconv.ParseUint(fields[13], 10, 64)
	stime, err2 := strconv.ParseUint(fields[14], 10, 64)
	if err1 != nil || err2 != nil {
		return 0.0
	}
	totalTime := utime + stime

	prev, exists := d.cpuHistory[pid]
	d.cpuHistory[pid] = ProcessCPUHistory{
		TotalTime: totalTime,
		Timestamp: now,
	}

	if !exists {
		return 0.5
	}

	elapsedSec := now.Sub(prev.Timestamp).Seconds()
	if elapsedSec <= 0.001 {
		return 0.0
	}

	ticksDelta := float64(totalTime - prev.TotalTime)
	cpuPct := (ticksDelta / d.clkTck) / elapsedSec * 100.0
	if cpuPct < 0 {
		cpuPct = 0
	}
	if cpuPct > 800 {
		cpuPct = 800
	}

	return float64(int(cpuPct*10)) / 10.0
}

func readUptime(pid int) int64 {
	statData, err := os.ReadFile(filepath.Join("/proc", strconv.Itoa(pid), "stat"))
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(statData))
	if len(fields) < 22 {
		return 0
	}

	startTimeTicks, err := strconv.ParseUint(fields[21], 10, 64)
	if err != nil {
		return 0
	}

	upData, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	upFields := strings.Fields(string(upData))
	if len(upFields) < 1 {
		return 0
	}
	sysUptimeSec, _ := strconv.ParseFloat(upFields[0], 64)

	processStartSec := float64(startTimeTicks) / 100.0
	uptime := sysUptimeSec - processStartSec
	if uptime < 0 {
		uptime = 0
	}
	return int64(uptime)
}

func formatAppName(mainClass, jarPath, cwd string, args []string) string {
	cwdBase := filepath.Base(cwd)

	// Check if it's NetBeans
	if strings.Contains(mainClass, "netbeans") || strings.Contains(strings.Join(args, " "), "netbeans") {
		return "Apache NetBeans IDE"
	}

	// Check if it's RedHat / Eclipse Language Server
	if strings.Contains(mainClass, "equinox") || strings.Contains(strings.Join(args, " "), "jdt.ls") {
		return "Eclipse JDT Language Server"
	}

	// Check if it's Gradle
	if strings.Contains(mainClass, "GradleDaemon") {
		return fmt.Sprintf("Gradle Daemon [%s]", cwdBase)
	}
	if strings.Contains(mainClass, "GradleServer") {
		return "Gradle Server"
	}

	// Check if it's Maven
	if strings.Contains(mainClass, "classworlds") || strings.Contains(strings.Join(args, " "), "maven") {
		return fmt.Sprintf("Maven Daemon [%s]", cwdBase)
	}

	// Check if it's Jettra related
	if strings.Contains(mainClass, "jettra") || strings.Contains(jarPath, "jettra") || strings.Contains(jarPath, "Jettra") || strings.Contains(strings.Join(args, " "), "jettra") {
		label := ""
		if mainClass != "" {
			parts := strings.Split(mainClass, ".")
			label = parts[len(parts)-1]
		} else if jarPath != "" {
			label = filepath.Base(jarPath)
			label = strings.TrimSuffix(label, ".jar")
		}
		if label != "" && cwdBase != "" && cwdBase != "." && cwdBase != "/" {
			return fmt.Sprintf("%s [%s]", label, cwdBase)
		} else if label != "" {
			return label
		}
		if cwdBase != "" && cwdBase != "." && cwdBase != "/" {
			return fmt.Sprintf("Jettra Service [%s]", cwdBase)
		}
		return "Jettra Application"
	}

	// Check if JAR was specified
	if jarPath != "" {
		jarName := filepath.Base(jarPath)
		if cwdBase != "" && cwdBase != "." {
			return fmt.Sprintf("%s [%s]", jarName, cwdBase)
		}
		return jarName
	}

	// Check Main-Class
	if mainClass != "" {
		parts := strings.Split(mainClass, ".")
		simple := parts[len(parts)-1]
		if len(parts) > 1 {
			prefix := parts[len(parts)-2]
			return fmt.Sprintf("%s.%s", prefix, simple)
		}
		return simple
	}

	if cwdBase != "" && cwdBase != "/" {
		return fmt.Sprintf("Java App [%s]", cwdBase)
	}
	return "Java Application"
}

func generateAppID(cwd, mainClass, jarPath string, args []string) string {
	h := sha256.New()
	h.Write([]byte(cwd))
	h.Write([]byte(mainClass))
	h.Write([]byte(jarPath))

	for _, a := range args {
		if strings.HasPrefix(a, "-D") || strings.HasPrefix(a, "--") || !strings.HasPrefix(a, "-") {
			h.Write([]byte(a))
		}
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if len(sum) > 16 {
		return sum[:16]
	}
	return sum
}

func findJpsBinary() string {
	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		p := filepath.Join(jh, "bin", "jps")
		if fileExists(p) {
			return p
		}
	}
	if fileExists("/usr/local/jdk-25.0.3/bin/jps") {
		return "/usr/local/jdk-25.0.3/bin/jps"
	}
	if p, err := exec.LookPath("jps"); err == nil {
		return p
	}
	return ""
}

func findJavaBinary() string {
	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		p := filepath.Join(jh, "bin", "java")
		if fileExists(p) {
			return p
		}
	}
	if fileExists("/usr/local/jdk-25.0.3/bin/java") {
		return "/usr/local/jdk-25.0.3/bin/java"
	}
	if p, err := exec.LookPath("java"); err == nil {
		return p
	}
	return "java"
}

func formatBytes(b uint64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := uint64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}
