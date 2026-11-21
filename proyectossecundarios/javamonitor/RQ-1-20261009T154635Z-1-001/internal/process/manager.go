package process

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Manager struct {
	mu          sync.RWMutex
	apps        map[string]*JavaApp
	hiddenIDs   map[string]bool
	detector    *Detector
	subscribers map[chan []JavaApp]bool
	subMu       sync.Mutex
	stopChan    chan struct{}
}

func NewManager() *Manager {
	return &Manager{
		apps:        make(map[string]*JavaApp),
		hiddenIDs:   make(map[string]bool),
		detector:    NewDetector(),
		subscribers: make(map[chan []JavaApp]bool),
		stopChan:    make(chan struct{}),
	}
}

// StartMonitoring begins the periodic discovery loop
func (m *Manager) StartMonitoring(ctx context.Context, interval time.Duration) {
	// Initial scan
	m.Refresh()

	ticker := time.NewTicker(interval)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-m.stopChan:
				return
			case <-ticker.C:
				m.Refresh()
			}
		}
	}()
}

func (m *Manager) StopMonitoring() {
	close(m.stopChan)
}

// Refresh performs a scan and updates state
func (m *Manager) Refresh() {
	scannedApps, err := m.detector.Scan()
	if err != nil {
		return
	}

	m.mu.Lock()
	scannedMap := make(map[string]*JavaApp)
	for i := range scannedApps {
		app := scannedApps[i]
		scannedMap[app.ID] = &app
	}

	// Update existing apps or add new ones (skip hidden cleared processes)
	for id, scanned := range scannedMap {
		if m.hiddenIDs[id] {
			continue
		}
		if existing, found := m.apps[id]; found {
			// Update live dynamic metrics
			existing.PID = scanned.PID
			existing.Status = "RUNNING"
			existing.MemoryRSS = scanned.MemoryRSS
			existing.MemoryVMS = scanned.MemoryVMS
			existing.MemoryStr = scanned.MemoryStr
			existing.CPUPercent = scanned.CPUPercent
			existing.Threads = scanned.Threads
			existing.UptimeSec = scanned.UptimeSec
			// Keep launch info intact
			if len(scanned.Args) > 0 {
				existing.Args = scanned.Args
				existing.Executable = scanned.Executable
			}
		} else {
			m.apps[id] = scanned
		}
	}

	// Mark disappeared apps as STOPPED
	for id, app := range m.apps {
		if _, stillRunning := scannedMap[id]; !stillRunning {
			if app.Status == "RUNNING" {
				app.Status = "STOPPED"
				app.CPUPercent = 0.0
				app.MemoryRSS = 0
				app.MemoryStr = "0 B (Detenida)"
				app.Threads = 0
			}
		}
	}

	// Snapshot for subscribers
	allApps := make([]JavaApp, 0, len(m.apps))
	for _, a := range m.apps {
		allApps = append(allApps, *a)
	}
	m.mu.Unlock()

	m.notifySubscribers(allApps)
}

// ClearScreen removes all processes from the screen/registry and hides currently active ones until Reset or Refresh
func (m *Manager) ClearScreen() {
	m.mu.Lock()
	for id := range m.apps {
		m.hiddenIDs[id] = true
	}
	m.apps = make(map[string]*JavaApp)
	m.mu.Unlock()

	m.notifySubscribers([]JavaApp{})
}

// ResetAll clears all stored/hidden process state and triggers a fresh scan of live Java processes
func (m *Manager) ResetAll() {
	m.mu.Lock()
	m.apps = make(map[string]*JavaApp)
	m.hiddenIDs = make(map[string]bool)
	m.mu.Unlock()

	m.Refresh()
}

// GetAllApps returns a snapshot of all registered Java apps
func (m *Manager) GetAllApps() []JavaApp {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]JavaApp, 0, len(m.apps))
	for _, app := range m.apps {
		result = append(result, *app)
	}
	return result
}

// GetApp returns an app by ID
func (m *Manager) GetApp(id string) (*JavaApp, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	app, found := m.apps[id]
	if !found {
		return nil, false
	}
	cp := *app
	return &cp, true
}

// StopApp stops a running Java application gracefully (SIGTERM) or forcefully (SIGKILL)
func (m *Manager) StopApp(id string, force bool) error {
	m.mu.Lock()
	app, found := m.apps[id]
	if !found {
		m.mu.Unlock()
		return fmt.Errorf("aplicación con id '%s' no encontrada", id)
	}

	if app.Status == "STOPPED" {
		m.mu.Unlock()
		return nil // Already stopped
	}

	pid := app.PID
	m.mu.Unlock()

	proc, err := os.FindProcess(pid)
	if err != nil {
		m.markStopped(id, "Proceso no encontrado")
		return nil
	}

	sig := syscall.SIGTERM
	if force {
		sig = syscall.SIGKILL
	}

	if err := proc.Signal(sig); err != nil {
		// Could already be dead
		m.markStopped(id, "")
		return nil
	}

	// Wait up to 3 seconds for graceful exit
	if !force {
		done := make(chan bool, 1)
		go func() {
			for i := 0; i < 30; i++ {
				time.Sleep(100 * time.Millisecond)
				// Test if process is still alive with signal 0
				if err := proc.Signal(syscall.Signal(0)); err != nil {
					done <- true
					return
				}
			}
			done <- false
		}()

		killedGracefully := <-done
		if !killedGracefully {
			_ = proc.Signal(syscall.SIGKILL)
		}
	}

	m.markStopped(id, "")
	m.Refresh()
	return nil
}

// StartApp starts a previously stopped application using its preserved arguments & cwd
func (m *Manager) StartApp(id string) (*JavaApp, error) {
	m.mu.Lock()
	app, found := m.apps[id]
	if !found {
		m.mu.Unlock()
		return nil, fmt.Errorf("aplicación con id '%s' no encontrada", id)
	}

	if app.Status == "RUNNING" {
		// Verify if it is really running
		if proc, err := os.FindProcess(app.PID); err == nil {
			if err := proc.Signal(syscall.Signal(0)); err == nil {
				m.mu.Unlock()
				return app, errors.New("la aplicación ya se encuentra en ejecución")
			}
		}
	}

	if len(app.Args) == 0 {
		m.mu.Unlock()
		return nil, errors.New("no hay comando de ejecución guardado para esta aplicación")
	}

	exe := app.Executable
	if exe == "" || !fileExists(exe) {
		// Fallback to java in path or standard location
		if fileExists("/usr/local/jdk-25.0.3/bin/java") {
			exe = "/usr/local/jdk-25.0.3/bin/java"
		} else {
			exe = "java"
		}
	}

	// The first argument is typically the binary name, rest are parameters
	var cmdArgs []string
	if len(app.Args) > 1 {
		cmdArgs = app.Args[1:]
	}

	cmd := exec.Command(exe, cmdArgs...)
	if app.Cwd != "" && dirExists(app.Cwd) {
		cmd.Dir = app.Cwd
	} else {
		cmd.Dir = os.Getenv("HOME")
	}

	// Detach or background execution
	cmd.SysProcAttr = &syscall.SysProcAttr{
		Setpgid: true,
	}

	if err := cmd.Start(); err != nil {
		app.Status = "FAILED"
		app.LastExitErr = err.Error()
		m.mu.Unlock()
		return nil, fmt.Errorf("error al iniciar la aplicación: %w", err)
	}

	newPID := cmd.Process.Pid
	app.PID = newPID
	app.Status = "RUNNING"
	app.StartTime = time.Now()
	app.UptimeSec = 0
	app.LastExitErr = ""
	appCopy := *app
	m.mu.Unlock()

	// Asynchronously monitor termination
	go func(appID string, p *os.Process) {
		_, _ = p.Wait()
		m.markStopped(appID, "Proceso terminado")
		m.Refresh()
	}(id, cmd.Process)

	// Immediate quick refresh
	go func() {
		time.Sleep(300 * time.Millisecond)
		m.Refresh()
	}()

	return &appCopy, nil
}

// RestartApp restarts an app by stopping and starting it
func (m *Manager) RestartApp(id string) (*JavaApp, error) {
	_ = m.StopApp(id, false)
	time.Sleep(500 * time.Millisecond)
	return m.StartApp(id)
}

// LaunchCustom creates and launches a new Java app from a command
func (m *Manager) LaunchCustom(req LaunchReq) (*JavaApp, error) {
	cmdStr := strings.TrimSpace(req.Command)
	if cmdStr == "" {
		return nil, errors.New("el comando no puede estar vacío")
	}

	cwd := strings.TrimSpace(req.Cwd)
	if cwd == "" || !dirExists(cwd) {
		cwd = os.Getenv("HOME")
	}

	parts := strings.Fields(cmdStr)
	exe := parts[0]
	var args []string
	if len(parts) > 1 {
		args = parts[1:]
	}

	cmd := exec.Command(exe, args...)
	cmd.Dir = cwd
	cmd.SysProcAttr = &syscall.SysProcAttr{
		Setpgid: true,
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("error al ejecutar comando: %w", err)
	}

	newPID := cmd.Process.Pid
	now := time.Now()

	vmArgs, appArgs, mainClass, jarPath := parseJavaArgs(parts)
	name := req.Name
	if name == "" {
		name = formatAppName(mainClass, jarPath, cwd, parts)
	}

	id := generateAppID(cwd, mainClass, jarPath, parts)

	app := &JavaApp{
		ID:         id,
		PID:        newPID,
		Name:       name,
		MainClass:  mainClass,
		JarPath:    jarPath,
		Executable: exe,
		Args:       parts,
		VMArgs:     vmArgs,
		AppArgs:    appArgs,
		Cwd:        cwd,
		Status:     "RUNNING",
		StartTime:  now,
		UptimeSec:  0,
		CanRestart: true,
	}

	m.mu.Lock()
	m.apps[id] = app
	m.mu.Unlock()

	go func(appID string, p *os.Process) {
		_, _ = p.Wait()
		m.markStopped(appID, "Proceso terminado")
		m.Refresh()
	}(id, cmd.Process)

	go func() {
		time.Sleep(300 * time.Millisecond)
		m.Refresh()
	}()

	return app, nil
}

func (m *Manager) markStopped(id string, errMsg string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if app, found := m.apps[id]; found {
		app.Status = "STOPPED"
		app.CPUPercent = 0.0
		app.MemoryRSS = 0
		app.MemoryStr = "0 B (Detenida)"
		app.Threads = 0
		if errMsg != "" {
			app.LastExitErr = errMsg
		}
	}
}

// GetStats returns aggregated metrics
func (m *Manager) GetStats() SystemStats {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var totalMem uint64
	var totalCPU float64
	running := 0
	stopped := 0

	for _, app := range m.apps {
		if app.Status == "RUNNING" {
			running++
			totalMem += app.MemoryRSS
			totalCPU += app.CPUPercent
		} else {
			stopped++
		}
	}

	avgCPU := 0.0
	if running > 0 {
		avgCPU = totalCPU / float64(running)
	}

	return SystemStats{
		TotalApps:        len(m.apps),
		RunningApps:      running,
		StoppedApps:      stopped,
		TotalMemoryBytes: totalMem,
		TotalMemoryStr:   formatBytes(totalMem),
		AvgCPU:           float64(int(avgCPU*10)) / 10.0,
		Timestamp:        time.Now().Unix(),
	}
}

// Subscribe returns a channel that receives updates whenever apps change
func (m *Manager) Subscribe() chan []JavaApp {
	m.subMu.Lock()
	defer m.subMu.Unlock()
	ch := make(chan []JavaApp, 5)
	m.subscribers[ch] = true
	return ch
}

func (m *Manager) Unsubscribe(ch chan []JavaApp) {
	m.subMu.Lock()
	defer m.subMu.Unlock()
	delete(m.subscribers, ch)
	close(ch)
}

func (m *Manager) notifySubscribers(apps []JavaApp) {
	m.subMu.Lock()
	defer m.subMu.Unlock()
	for ch := range m.subscribers {
		select {
		case ch <- apps:
		default:
			// Non-blocking drop if consumer is slow
		}
	}
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

