package demo

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"java3d-monitor/internal/process"
)

// SpawnDemoApp compiles and launches a demo Java application for safe testing
func SpawnDemoApp(mgr *process.Manager, customName string) (*process.JavaApp, error) {
	demoDir := filepath.Join(os.TempDir(), "java3d-monitor-demo")
	if err := os.MkdirAll(demoDir, 0755); err != nil {
		return nil, fmt.Errorf("error creating demo directory: %w", err)
	}

	javaSource := `
public class Java3DDemoApp {
    public static void main(String[] args) {
        String serviceName = args.length > 0 ? args[0] : "Demo-Service";
        System.out.println(">>> [" + serviceName + "] Java 3D Monitor Test Service Started!");
        long counter = 0;
        try {
            while (true) {
                counter++;
                long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                if (counter % 10 == 0) {
                    System.out.println("Heartbeat #" + counter + " | Memory: " + (totalMem - freeMem) + "/" + totalMem + " MB");
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("Demo service interrupted, shutting down.");
        }
    }
}
`
	srcFile := filepath.Join(demoDir, "Java3DDemoApp.java")
	if err := os.WriteFile(srcFile, []byte(javaSource), 0644); err != nil {
		return nil, fmt.Errorf("error writing demo source: %w", err)
	}

	// Find javac
	javac := "javac"
	if fileExists("/usr/local/jdk-25.0.3/bin/javac") {
		javac = "/usr/local/jdk-25.0.3/bin/javac"
	}

	compileCmd := exec.Command(javac, srcFile)
	compileCmd.Dir = demoDir
	if out, err := compileCmd.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("error compiling demo Java app: %v (%s)", err, string(out))
	}

	// Find java
	javaExe := "java"
	if fileExists("/usr/local/jdk-25.0.3/bin/java") {
		javaExe = "/usr/local/jdk-25.0.3/bin/java"
	}

	appInstanceName := customName
	if appInstanceName == "" {
		appInstanceName = fmt.Sprintf("Microservicio-3D-%d", time.Now().Unix()%1000)
	}

	cmdStr := fmt.Sprintf("%s -Xms32m -Xmx128m -cp %s Java3DDemoApp %s", javaExe, demoDir, appInstanceName)

	return mgr.LaunchCustom(process.LaunchReq{
		Name:    fmt.Sprintf("Demo [%s]", appInstanceName),
		Command: cmdStr,
		Cwd:     demoDir,
	})
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

