package process

import "time"

// JavaApp represents a tracked Java application instance
type JavaApp struct {
	ID          string    `json:"id"`
	PID         int       `json:"pid"`
	Name        string    `json:"name"`
	MainClass   string    `json:"main_class"`
	JarPath     string    `json:"jar_path"`
	Executable  string    `json:"executable"`
	Args        []string  `json:"args"`
	VMArgs      []string  `json:"vm_args"`
	AppArgs     []string  `json:"app_args"`
	Cwd         string    `json:"cwd"`
	Status      string    `json:"status"` // "RUNNING", "STOPPED", "STARTING", "FAILED"
	StartTime   time.Time `json:"start_time"`
	UptimeSec   int64     `json:"uptime_sec"`
	MemoryRSS   uint64    `json:"memory_rss"`
	MemoryVMS   uint64    `json:"memory_vms"`
	MemoryStr   string    `json:"memory_str"`
	CPUPercent  float64   `json:"cpu_percent"`
	Threads     int       `json:"threads"`
	LastExitErr string    `json:"last_exit_err,omitempty"`
	IsDemo      bool      `json:"is_demo"`
	CanRestart  bool      `json:"can_restart"`
}

// SystemStats represents aggregate metrics of monitored Java processes
type SystemStats struct {
	TotalApps        int     `json:"total_apps"`
	RunningApps      int     `json:"running_apps"`
	StoppedApps      int     `json:"stopped_apps"`
	TotalMemoryBytes uint64  `json:"total_memory_bytes"`
	TotalMemoryStr   string  `json:"total_memory_str"`
	AvgCPU           float64 `json:"avg_cpu"`
	Timestamp        int64   `json:"timestamp"`
}

// ActionReq represents payload for start/stop/restart
type ActionReq struct {
	ID    string `json:"id"`
	Force bool   `json:"force,omitempty"`
}

// LaunchReq represents payload for launching a new application
type LaunchReq struct {
	Name    string   `json:"name"`
	Command string   `json:"command"`
	Args    []string `json:"args,omitempty"`
	Cwd     string   `json:"cwd,omitempty"`
}

// ApiResponse standard envelope
type ApiResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
	Data    any    `json:"data,omitempty"`
}

