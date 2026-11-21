package main

import (
	"context"
	"embed"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"java3d-monitor/internal/demo"
	"java3d-monitor/internal/process"
)

//go:embed web/*
var webFS embed.FS

func main() {
	port := flag.Int("port", 8989, "Puerto HTTP del servidor de la interfaz 3D")
	interval := flag.Duration("interval", 1500*time.Millisecond, "Intervalo de escaneo de procesos Java")
	noBrowser := flag.Bool("no-browser", false, "No abrir automáticamente el navegador web")
	flag.Parse()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Initialize process manager
	mgr := process.NewManager()
	mgr.StartMonitoring(ctx, *interval)
	defer mgr.StopMonitoring()

	// Setup HTTP router
	mux := http.NewServeMux()

	// API Handlers
	mux.HandleFunc("GET /api/apps", handleGetApps(mgr))
	mux.HandleFunc("GET /api/stats", handleGetStats(mgr))
	mux.HandleFunc("POST /api/apps/stop", handleStopApp(mgr))
	mux.HandleFunc("POST /api/apps/start", handleStartApp(mgr))
	mux.HandleFunc("POST /api/apps/restart", handleRestartApp(mgr))
	mux.HandleFunc("POST /api/apps/launch", handleLaunchApp(mgr))
	mux.HandleFunc("POST /api/apps/clear", handleClearScreen(mgr))
	mux.HandleFunc("POST /api/apps/reset", handleResetAll(mgr))
	mux.HandleFunc("POST /api/demo/create", handleSpawnDemo(mgr))
	mux.HandleFunc("GET /api/stream", handleSSEStream(mgr))

	// Embedded Static File Server
	webContent, err := fs.Sub(webFS, "web")
	if err != nil {
		log.Fatalf("Error inicializando embedded web files: %v", err)
	}
	fileServer := http.FileServer(http.FS(webContent))
	mux.Handle("/", fileServer)

	serverAddr := fmt.Sprintf(":%d", *port)
	server := &http.Server{
		Addr:    serverAddr,
		Handler: corsMiddleware(mux),
	}

	// Channel for graceful shutdown
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	go func() {
		printBanner(*port)
		if !*noBrowser {
			time.Sleep(400 * time.Millisecond)
			openBrowser(fmt.Sprintf("http://localhost:%d", *port))
		}
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Fallo en el servidor HTTP: %v", err)
		}
	}()

	<-stop
	fmt.Println("\n[JVM 3D] Apagando servidor con gracia...")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)
	fmt.Println("[JVM 3D] Servidor detenido. ¡Hasta pronto!")
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func handleGetApps(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		apps := mgr.GetAllApps()
		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Data:    apps,
		})
	}
}

func handleGetStats(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		stats := mgr.GetStats()
		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Data:    stats,
		})
	}
}

func handleStopApp(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req process.ActionReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, process.ApiResponse{Success: false, Message: "Payload inválido"})
			return
		}

		if err := mgr.StopApp(req.ID, req.Force); err != nil {
			writeJSON(w, http.StatusInternalServerError, process.ApiResponse{Success: false, Message: err.Error()})
			return
		}

		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Aplicación Java detenida correctamente",
		})
	}
}

func handleStartApp(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req process.ActionReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, process.ApiResponse{Success: false, Message: "Payload inválido"})
			return
		}

		app, err := mgr.StartApp(req.ID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, process.ApiResponse{Success: false, Message: err.Error()})
			return
		}

		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Aplicación Java iniciada exitosamente",
			Data:    app,
		})
	}
}

func handleRestartApp(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req process.ActionReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, process.ApiResponse{Success: false, Message: "Payload inválido"})
			return
		}

		app, err := mgr.RestartApp(req.ID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, process.ApiResponse{Success: false, Message: err.Error()})
			return
		}

		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Aplicación Java reiniciada exitosamente",
			Data:    app,
		})
	}
}

func handleLaunchApp(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req process.LaunchReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, process.ApiResponse{Success: false, Message: "Payload inválido"})
			return
		}

		app, err := mgr.LaunchCustom(req)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, process.ApiResponse{Success: false, Message: err.Error()})
			return
		}

		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Nueva aplicación iniciada exitosamente",
			Data:    app,
		})
	}
}

func handleClearScreen(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		mgr.ClearScreen()
		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Todos los procesos han sido limpiados de la pantalla",
			Data:    []process.JavaApp{},
		})
	}
}

func handleResetAll(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		mgr.ResetAll()
		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Monitor reseteado completamente y procesos re-escaneados",
			Data:    mgr.GetAllApps(),
		})
	}
}

func handleSpawnDemo(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		app, err := demo.SpawnDemoApp(mgr, "")
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, process.ApiResponse{Success: false, Message: err.Error()})
			return
		}

		writeJSON(w, http.StatusOK, process.ApiResponse{
			Success: true,
			Message: "Microservicio Java de prueba creado y en ejecución",
			Data:    app,
		})
	}
}

// handleSSEStream pushes updates in real-time
func handleSSEStream(mgr *process.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "Streaming no soportado", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")

		// Send initial state
		initialApps := mgr.GetAllApps()
		if data, err := json.Marshal(initialApps); err == nil {
			fmt.Fprintf(w, "event: apps\ndata: %s\n\n", data)
			flusher.Flush()
		}

		initialStats := mgr.GetStats()
		if data, err := json.Marshal(initialStats); err == nil {
			fmt.Fprintf(w, "event: stats\ndata: %s\n\n", data)
			flusher.Flush()
		}

		ch := mgr.Subscribe()
		defer mgr.Unsubscribe(ch)

		ticker := time.NewTicker(1000 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-r.Context().Done():
				return
			case apps, open := <-ch:
				if !open {
					return
				}
				if data, err := json.Marshal(apps); err == nil {
					fmt.Fprintf(w, "event: apps\ndata: %s\n\n", data)
					flusher.Flush()
				}
			case <-ticker.C:
				stats := mgr.GetStats()
				if data, err := json.Marshal(stats); err == nil {
					fmt.Fprintf(w, "event: stats\ndata: %s\n\n", data)
					flusher.Flush()
				}
			}
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func printBanner(port int) {
	fmt.Println("==================================================================")
	fmt.Println("  ╦  ╦╦  ╦╔╦╗  ╔═╗╔╦╗  ╔╦╗╔═╗╔╦╗╦═╗╦═╗ ╦═╗")
	fmt.Println("  ║  ║╚╗╔╝║║║  ══╗ ║║  ║║║╠═╣ ║ ╠╦╝╠╦╝ ╔═╝")
	fmt.Println("  ╚═╝╩ ╚╝ ╩ ╩  ╚═╝═╩╝  ╩ ╩╩ ╩ ╩ ╩╚═╩╚═ ╚═╝")
	fmt.Println("  Monitor 3D de Aplicaciones Java en Golang")
	fmt.Println("==================================================================")
	fmt.Printf("  ➜ URL de la Interfaz 3D:  http://localhost:%d\n", port)
	fmt.Printf("  ➜ API REST y Streaming:   http://localhost:%d/api/apps\n", port)
	fmt.Println("  ➜ Presiona Ctrl+C para finalizar.")
	fmt.Println("==================================================================")
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "linux":
		cmd = exec.Command("xdg-open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	}
	if cmd != nil {
		_ = cmd.Start()
	}
}

