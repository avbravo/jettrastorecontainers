/**
 * JVM 3D MATRIX - 3D Interface for Java Process Management
 * Three.js + WebGL Interactive Holo-Grid
 */

(function () {
  'use strict';

  // State Management
  let scene, camera, renderer, controls;
  let raycaster, mouse;
  const nodes = new Map(); // id -> Node3D
  let selectedNodeId = null;
  let hoveredNode = null;
  let isAutoRotating = false;
  let currentFilter = '';
  let eventSource = null;
  let particlesMesh = null;
  let clock = new THREE.Clock();

  // DOM Elements
  const container = document.getElementById('canvas-container');
  const inspector = document.getElementById('inspector-panel');
  const searchInput = document.getElementById('search-input');
  const btnRefreshApps = document.getElementById('btn-refresh-apps');
  const btnClearScreen = document.getElementById('btn-clear-screen');
  const btnResetAll = document.getElementById('btn-reset-all');
  const btnSpawnDemo = document.getElementById('btn-spawn-demo');
  const btnOpenLaunch = document.getElementById('btn-open-launch');
  const launchModal = document.getElementById('launch-modal');
  const launchForm = document.getElementById('launch-form');
  const btnCloseModal = document.getElementById('btn-close-modal');
  const btnCancelModal = document.getElementById('btn-cancel-modal');
  const btnCloseInspector = document.getElementById('btn-close-inspector');
  const btnAutoRotate = document.getElementById('btn-autorotate');
  const btnResetCam = document.getElementById('btn-reset-cam');
  const camButtons = document.querySelectorAll('.cam-btn[data-view]');

  // Inspector Elements
  const inspTitle = document.getElementById('insp-title');
  const inspBadge = document.getElementById('insp-status-badge');
  const inspPid = document.getElementById('insp-pid');
  const inspMemStr = document.getElementById('insp-mem-str');
  const inspMemBar = document.getElementById('insp-mem-bar');
  const inspVmsStr = document.getElementById('insp-vms-str');
  const inspCpuStr = document.getElementById('insp-cpu-str');
  const inspCpuBar = document.getElementById('insp-cpu-bar');
  const inspThreadsStr = document.getElementById('insp-threads-str');
  const inspUptime = document.getElementById('insp-uptime');
  const inspCwd = document.getElementById('insp-cwd');
  const inspMainClass = document.getElementById('insp-mainclass');
  const inspExecutable = document.getElementById('insp-executable');
  const inspVmArgs = document.getElementById('insp-vmargs');
  const inspCmdLine = document.getElementById('insp-cmdline');
  const inspErrorBox = document.getElementById('insp-error-box');
  const inspErrorMsg = document.getElementById('insp-error-msg');
  const btnStopApp = document.getElementById('btn-stop-app');
  const btnStartApp = document.getElementById('btn-start-app');
  const btnRestartApp = document.getElementById('btn-restart-app');
  const btnKillApp = document.getElementById('btn-kill-app');
  const btnCopyCmd = document.getElementById('btn-copy-cmd');

  // Top Stats
  const statRunning = document.getElementById('stat-running');
  const statStopped = document.getElementById('stat-stopped');
  const statMemory = document.getElementById('stat-memory');
  const statCpu = document.getElementById('stat-cpu');

  // Initialize Three.js Scene
  function init3D() {
    const width = container.clientWidth || window.innerWidth;
    const height = container.clientHeight || window.innerHeight;

    // Scene & Fog
    scene = new THREE.Scene();
    scene.background = new THREE.Color(0x060913);
    scene.fog = new THREE.FogExp2(0x060913, 0.012);

    // Camera
    camera = new THREE.PerspectiveCamera(45, width / height, 0.5, 1000);
    camera.position.set(30, 26, 32);

    // Renderer
    renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.2;
    container.appendChild(renderer.domElement);

    // Controls
    controls = new THREE.OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.06;
    controls.maxPolarAngle = Math.PI / 2 - 0.03; // Don't go below floor
    controls.minDistance = 6;
    controls.maxDistance = 180;
    controls.target.set(0, 1.5, 0);

    // Initial safe view positioning
    setCameraView('iso');

    // Raycaster
    raycaster = new THREE.Raycaster();
    mouse = new THREE.Vector2();

    // Lighting
    setupLighting();

    // Cyber Environment (Grid + Particles)
    setupEnvironment();

    // Event Listeners
    window.addEventListener('resize', onWindowResize);
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('click', onClick);

    // Start Render Loop
    animate();
  }

  function setupLighting() {
    // Ambient Light
    const ambientLight = new THREE.AmbientLight(0x1a2638, 1.6);
    scene.add(ambientLight);

    // Directional Sunlight (Key)
    const dirLight = new THREE.DirectionalLight(0x80d8ff, 1.4);
    dirLight.position.set(30, 60, 40);
    dirLight.castShadow = true;
    dirLight.shadow.mapSize.width = 1024;
    dirLight.shadow.mapSize.height = 1024;
    scene.add(dirLight);

    // Secondary Fill Light
    const fillLight = new THREE.DirectionalLight(0x00f5a0, 0.6);
    fillLight.position.set(-30, 30, -30);
    scene.add(fillLight);

    // Center Holo Point Light
    const centerLight = new THREE.PointLight(0x00e5ff, 2.5, 90);
    centerLight.position.set(0, 6, 0);
    scene.add(centerLight);
  }

  function setupEnvironment() {
    // Cyber Grid Floor
    const gridHelper = new THREE.GridHelper(160, 40, 0x00e5ff, 0x142038);
    gridHelper.position.y = -0.01;
    scene.add(gridHelper);

    // Concentric Holo Rings on Floor
    for (let r of [15, 30, 45, 60]) {
      const ringGeo = new THREE.RingGeometry(r - 0.1, r + 0.1, 64);
      const ringMat = new THREE.MeshBasicMaterial({
        color: 0x00e5ff,
        transparent: true,
        opacity: 0.15,
        side: THREE.DoubleSide,
      });
      const ring = new THREE.Mesh(ringGeo, ringMat);
      ring.rotation.x = -Math.PI / 2;
      ring.position.y = 0.01;
      scene.add(ring);
    }

    // Upward Floating Data Particles
    const particleCount = 600;
    const geometry = new THREE.BufferGeometry();
    const positions = new Float32Array(particleCount * 3);
    const colors = new Float32Array(particleCount * 3);

    for (let i = 0; i < particleCount; i++) {
      positions[i * 3] = (Math.random() - 0.5) * 120;
      positions[i * 3 + 1] = Math.random() * 40;
      positions[i * 3 + 2] = (Math.random() - 0.5) * 120;

      // Cyan to Emerald
      colors[i * 3] = 0.0;
      colors[i * 3 + 1] = 0.8 + Math.random() * 0.2;
      colors[i * 3 + 2] = 0.8 + Math.random() * 0.2;
    }

    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));

    const particleMat = new THREE.PointsMaterial({
      size: 0.35,
      vertexColors: true,
      transparent: true,
      opacity: 0.5,
      blending: THREE.AdditiveBlending,
    });

    particlesMesh = new THREE.Points(geometry, particleMat);
    scene.add(particlesMesh);
  }

  // Camera Presets
  function setCameraView(viewType) {
    if (!controls || !camera) return;

    let targetPos, targetLook;
    if (viewType === 'iso') {
      targetPos = { x: 30, y: 26, z: 32 };
      targetLook = { x: 0, y: 1.5, z: 0 };
    } else if (viewType === 'top') {
      targetPos = { x: 0.01, y: 55, z: 0.01 };
      targetLook = { x: 0, y: 0, z: 0 };
    } else if (viewType === 'orbit') {
      targetPos = { x: 0, y: 16, z: 42 };
      targetLook = { x: 0, y: 2, z: 0 };
    }

    if (typeof TWEEN !== 'undefined' && TWEEN.Tween) {
      new TWEEN.Tween(camera.position)
        .to(targetPos, 800)
        .easing(TWEEN.Easing.Cubic.Out)
        .start();
      new TWEEN.Tween(controls.target)
        .to(targetLook, 800)
        .easing(TWEEN.Easing.Cubic.Out)
        .start();
    } else {
      camera.position.set(targetPos.x, targetPos.y, targetPos.z);
      controls.target.set(targetLook.x, targetLook.y, targetLook.z);
      camera.lookAt(targetLook.x, targetLook.y, targetLook.z);
    }
  }

  // Node3D Object Builder
  class Node3D {
    constructor(app) {
      this.app = app;
      this.id = app.id;
      this.group = new THREE.Group();
      this.group.userData = { id: this.id, isNode: true };

      this.targetPos = new THREE.Vector3();
      this.currentPos = new THREE.Vector3();
      this.pulsePhase = Math.random() * Math.PI * 2;

      this.buildMesh();
      this.update(app);
      scene.add(this.group);
    }

    buildMesh() {
      // 1. Hexagonal Base Pedestal
      const baseGeo = new THREE.CylinderGeometry(1.9, 2.2, 0.45, 6);
      const baseMat = new THREE.MeshStandardMaterial({
        color: 0x101624,
        metalness: 0.85,
        roughness: 0.25,
      });
      this.baseMesh = new THREE.Mesh(baseGeo, baseMat);
      this.baseMesh.position.y = 0.22;
      this.baseMesh.receiveShadow = true;
      this.group.add(this.baseMesh);

      // Glowing Base Edge Ring
      const ringGeo = new THREE.RingGeometry(1.8, 2.15, 6);
      this.rimMat = new THREE.MeshBasicMaterial({
        color: 0x00e5ff,
        transparent: true,
        opacity: 0.8,
        side: THREE.DoubleSide,
      });
      const rim = new THREE.Mesh(ringGeo, this.rimMat);
      rim.rotation.x = -Math.PI / 2;
      rim.position.y = 0.46;
      this.group.add(rim);

      // 2. Central Floating Core
      const coreGeo = new THREE.BoxGeometry(1.5, 2.2, 1.5);
      this.coreMat = new THREE.MeshStandardMaterial({
        color: 0x00f5a0,
        emissive: 0x00f5a0,
        emissiveIntensity: 0.6,
        roughness: 0.15,
        metalness: 0.2,
        transparent: true,
        opacity: 0.9,
      });
      this.coreMesh = new THREE.Mesh(coreGeo, this.coreMat);
      this.coreMesh.position.y = 2.1;
      this.coreMesh.castShadow = true;
      this.coreMesh.userData = { isCore: true, id: this.id };
      this.group.add(this.coreMesh);

      // 3. Orbital Energy Rings
      const ring1Geo = new THREE.TorusGeometry(1.8, 0.035, 12, 48);
      const ring2Geo = new THREE.TorusGeometry(2.1, 0.035, 12, 48);
      this.energyRingMat = new THREE.MeshBasicMaterial({
        color: 0x00e5ff,
        transparent: true,
        opacity: 0.85,
      });
      this.ring1 = new THREE.Mesh(ring1Geo, this.energyRingMat);
      this.ring2 = new THREE.Mesh(ring2Geo, this.energyRingMat);
      this.ring1.rotation.x = Math.PI / 4;
      this.ring2.rotation.y = Math.PI / 3;
      this.ring1.position.y = 2.1;
      this.ring2.position.y = 2.1;
      this.group.add(this.ring1);
      this.group.add(this.ring2);

      // 4. Vertical Light Beacon (Active only when running)
      const beaconGeo = new THREE.CylinderGeometry(0.15, 0.6, 9, 16, 1, true);
      this.beaconMat = new THREE.MeshBasicMaterial({
        color: 0x00f5a0,
        transparent: true,
        opacity: 0.25,
        blending: THREE.AdditiveBlending,
        side: THREE.DoubleSide,
      });
      this.beacon = new THREE.Mesh(beaconGeo, this.beaconMat);
      this.beacon.position.y = 6.8;
      this.group.add(this.beacon);

      // 5. 3D Floating Sprite Label
      this.sprite = this.createSprite();
      this.sprite.position.y = 4.3;
      this.group.add(this.sprite);

      // Selection Marker
      const selGeo = new THREE.RingGeometry(2.4, 2.7, 32);
      this.selMat = new THREE.MeshBasicMaterial({
        color: 0x00e5ff,
        transparent: true,
        opacity: 0,
        side: THREE.DoubleSide,
      });
      this.selectionRing = new THREE.Mesh(selGeo, this.selMat);
      this.selectionRing.rotation.x = -Math.PI / 2;
      this.selectionRing.position.y = 0.03;
      this.group.add(this.selectionRing);
    }

    createSprite() {
      const canvas = document.createElement('canvas');
      canvas.width = 512;
      canvas.height = 256;
      const texture = new THREE.CanvasTexture(canvas);
      texture.minFilter = THREE.LinearFilter;
      const mat = new THREE.SpriteMaterial({
        map: texture,
        transparent: true,
        depthTest: false,
      });
      const sprite = new THREE.Sprite(mat);
      sprite.scale.set(4.2, 2.1, 1);
      this.canvas = canvas;
      this.texture = texture;
      return sprite;
    }

    renderSprite() {
      const ctx = this.canvas.getContext('2d');
      ctx.clearRect(0, 0, 512, 256);

      const isRunning = this.app.status === 'RUNNING';
      const isHighLoad = isRunning && this.app.cpu_percent >= 20;

      // Card Background
      ctx.fillStyle = 'rgba(8, 14, 26, 0.88)';
      ctx.strokeStyle = isRunning ? (isHighLoad ? '#ffb800' : '#00f5a0') : '#ff3366';
      ctx.lineWidth = 4;
      roundRect(ctx, 16, 16, 480, 224, 20);
      ctx.fill();
      ctx.stroke();

      // Status Pill
      ctx.font = 'bold 24px Rajdhani, sans-serif';
      const statusText = isRunning ? (isHighLoad ? 'CARGA ALTA' : 'EN EJECUCIÓN') : 'DETENIDA';
      const statusColor = isRunning ? (isHighLoad ? '#ffb800' : '#00f5a0') : '#ff3366';
      ctx.fillStyle = statusColor;
      ctx.fillText(statusText, 40, 58);

      // PID Badge
      ctx.font = '22px "JetBrains Mono", monospace';
      ctx.fillStyle = '#7ce8ff';
      const pidText = isRunning ? `PID: ${this.app.pid}` : 'PID: --';
      ctx.fillText(pidText, 340, 58);

      // Application Title
      ctx.font = 'bold 32px Rajdhani, sans-serif';
      ctx.fillStyle = '#ffffff';
      let title = this.app.name || 'Java Application';
      if (title.length > 24) title = title.substring(0, 22) + '...';
      ctx.fillText(title, 40, 115);

      // Metrics Row
      ctx.font = '24px "JetBrains Mono", monospace';
      ctx.fillStyle = '#8a99b5';
      const memStr = this.app.memory_str || '0 MB';
      const cpuStr = `${(this.app.cpu_percent || 0).toFixed(1)}% CPU`;
      const threadsStr = `${this.app.threads || 0} Hilos`;
      ctx.fillText(`${memStr}  |  ${cpuStr}  |  ${threadsStr}`, 40, 175);

      this.texture.needsUpdate = true;
    }

    update(app) {
      this.app = app;
      const isRunning = app.status === 'RUNNING';
      const isHighLoad = isRunning && app.cpu_percent >= 20;

      // Color Coding
      let primaryColor, emissiveColor;
      if (!isRunning) {
        primaryColor = 0x222a38;
        emissiveColor = 0xff3366;
      } else if (isHighLoad) {
        primaryColor = 0xffb800;
        emissiveColor = 0xff8800;
      } else {
        primaryColor = 0x00f5a0;
        emissiveColor = 0x00c6ff;
      }

      this.coreMat.color.setHex(primaryColor);
      this.coreMat.emissive.setHex(emissiveColor);
      this.coreMat.emissiveIntensity = isRunning ? (isHighLoad ? 0.9 : 0.6) : 0.15;
      this.rimMat.color.setHex(isRunning ? 0x00e5ff : 0xff3366);
      this.energyRingMat.color.setHex(isRunning ? 0x00e5ff : 0x555555);

      // Beacon visibility
      this.beacon.visible = isRunning;
      if (isRunning) {
        this.beaconMat.color.setHex(isHighLoad ? 0xffb800 : 0x00f5a0);
      }

      // Update Sprite Texture
      this.renderSprite();

      // Selection state
      const isSelected = selectedNodeId === this.id;
      this.selMat.opacity = isSelected ? 0.9 : 0;
    }

    animate(time, delta) {
      const isRunning = this.app.status === 'RUNNING';

      if (isRunning) {
        // Floating Core Bobbing
        this.coreMesh.position.y = 2.1 + Math.sin(time * 2.5 + this.pulsePhase) * 0.15;
        this.coreMesh.rotation.y += delta * 0.5;

        // Energy Rings Spinning (Speed tied to CPU %)
        const speed = 0.8 + (this.app.cpu_percent || 0) * 0.08;
        this.ring1.rotation.z += delta * speed;
        this.ring2.rotation.x += delta * speed * 0.8;
        this.ring1.position.y = this.coreMesh.position.y;
        this.ring2.position.y = this.coreMesh.position.y;

        // Beacon pulsing
        this.beaconMat.opacity = 0.2 + Math.sin(time * 4) * 0.1;
      } else {
        // Lower core to resting position
        this.coreMesh.position.y = 1.6;
        this.ring1.position.y = 1.6;
        this.ring2.position.y = 1.6;
        this.beaconMat.opacity = 0;
      }

      // Selection Ring Animation
      if (selectedNodeId === this.id) {
        this.selectionRing.rotation.z += delta * 1.5;
        this.selMat.opacity = 0.7 + Math.sin(time * 6) * 0.25;
      } else {
        this.selMat.opacity = 0;
      }

      // Smooth Position Interpolation
      this.group.position.lerp(this.targetPos, 0.08);

      // Filter Visibility
      if (currentFilter) {
        const match =
          (this.app.name && this.app.name.toLowerCase().includes(currentFilter)) ||
          String(this.app.pid).includes(currentFilter);
        this.group.visible = match;
      } else {
        this.group.visible = true;
      }
    }

    destroy() {
      scene.remove(this.group);
      this.texture.dispose();
      this.coreMat.dispose();
      this.baseMesh.geometry.dispose();
      this.coreMesh.geometry.dispose();
    }
  }

  // Helper for rounded canvas rectangles
  function roundRect(ctx, x, y, width, height, radius) {
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.lineTo(x + width - radius, y);
    ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
    ctx.lineTo(x + width, y + height - radius);
    ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
    ctx.lineTo(x + radius, y + height);
    ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
    ctx.lineTo(x, y + radius);
    ctx.quadraticCurveTo(x, y, x + radius, y);
    ctx.closePath();
  }

  // Layout Management: Compute positions for 3D nodes
  function updateLayout() {
    const list = Array.from(nodes.values());
    const count = list.length;
    if (count === 0) return;

    if (count <= 10) {
      // Sleek Holo-Ring Layout
      const radius = Math.max(12, count * 3.5);
      list.forEach((node, idx) => {
        const angle = (idx / count) * Math.PI * 2;
        node.targetPos.set(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
      });
    } else {
      // 3D Matrix Grid Layout
      const cols = Math.ceil(Math.sqrt(count));
      const spacing = 8.5;
      const offsetX = ((cols - 1) * spacing) / 2;
      const offsetZ = ((Math.ceil(count / cols) - 1) * spacing) / 2;

      list.forEach((node, idx) => {
        const col = idx % cols;
        const row = Math.floor(idx / cols);
        node.targetPos.set(col * spacing - offsetX, 0, row * spacing - offsetZ);
      });
    }
  }

  // Sync backend apps with 3D nodes
  function syncApps(appsList) {
    if (!Array.isArray(appsList)) return;

    const activeIds = new Set();

    appsList.forEach((app) => {
      activeIds.add(app.id);
      if (nodes.has(app.id)) {
        nodes.get(app.id).update(app);
      } else {
        const newNode = new Node3D(app);
        nodes.set(app.id, newNode);
      }
    });

    // Remove obsolete nodes if any
    for (const [id, node] of nodes.entries()) {
      if (!activeIds.has(id)) {
        node.destroy();
        nodes.delete(id);
      }
    }

    updateLayout();

    // Update inspector if open
    if (selectedNodeId && nodes.has(selectedNodeId)) {
      populateInspector(nodes.get(selectedNodeId).app);
    }
  }

  // Inspector Panel Population
  function populateInspector(app) {
    inspTitle.textContent = app.name || 'Java Application';
    inspPid.textContent = app.status === 'RUNNING' ? `PID: ${app.pid}` : 'PID: -- (Detenida)';

    // Status Badge
    inspBadge.textContent = app.status;
    inspBadge.className = `node-badge ${app.status.toLowerCase()}`;

    // Metrics
    inspMemStr.textContent = app.memory_str || '0 MB';
    const rssMB = app.memory_rss / (1024 * 1024);
    const memPct = Math.min(100, (rssMB / 2048) * 100);
    inspMemBar.style.width = `${memPct}%`;
    inspVmsStr.textContent = `Virtual (VMS): ${formatBytes(app.memory_vms)}`;

    inspCpuStr.textContent = `${(app.cpu_percent || 0).toFixed(1)}%`;
    inspCpuBar.style.width = `${Math.min(100, app.cpu_percent || 0)}%`;
    inspThreadsStr.textContent = `Hilos activos: ${app.threads || 0}`;

    // Details
    inspUptime.textContent = formatUptime(app.uptime_sec);
    inspCwd.textContent = app.cwd || '--';
    inspMainClass.textContent = app.jar_path ? `JAR: ${app.jar_path}` : (app.main_class || 'Main Class no detectada');
    inspExecutable.textContent = app.executable || 'java';

    // VM Args Chips
    inspVmArgs.innerHTML = '';
    if (app.vm_args && app.vm_args.length > 0) {
      app.vm_args.forEach((flag) => {
        const chip = document.createElement('span');
        chip.className = 'chip';
        chip.textContent = flag;
        inspVmArgs.appendChild(chip);
      });
    } else {
      inspVmArgs.innerHTML = '<span class="chip">Ninguno detectado</span>';
    }

    // Full Command Line
    inspCmdLine.textContent = (app.args && app.args.join(' ')) || 'java';

    // Errors / Exit Status
    if (app.last_exit_err) {
      inspErrorBox.classList.remove('hidden');
      inspErrorMsg.textContent = app.last_exit_err;
    } else {
      inspErrorBox.classList.add('hidden');
    }

    // Action Buttons Disabled/Enabled States
    const isRunning = app.status === 'RUNNING';
    btnStopApp.disabled = !isRunning;
    btnKillApp.disabled = !isRunning;
    btnStartApp.disabled = isRunning;
    btnRestartApp.disabled = false;

    inspector.classList.remove('hidden');
  }

  function selectNode(id) {
    selectedNodeId = id;

    nodes.forEach((node) => {
      node.update(node.app);
    });

    if (id && nodes.has(id)) {
      const node = nodes.get(id);
      populateInspector(node.app);

      // Smooth Camera Fly-in Focus
      if (typeof TWEEN !== 'undefined' && TWEEN.Tween) {
        const nPos = node.group.position;
        new TWEEN.Tween(camera.position)
          .to({ x: nPos.x + 9, y: nPos.y + 7, z: nPos.z + 10 }, 800)
          .easing(TWEEN.Easing.Cubic.Out)
          .start();

        new TWEEN.Tween(controls.target)
          .to({ x: nPos.x, y: nPos.y + 2, z: nPos.z }, 800)
          .easing(TWEEN.Easing.Cubic.Out)
          .start();
      }
    } else {
      inspector.classList.add('hidden');
    }
  }

  // Raycasting Handlers
  function onMouseMove(event) {
    mouse.x = (event.clientX / window.innerWidth) * 2 - 1;
    mouse.y = -(event.clientY / window.innerHeight) * 2 + 1;

    raycaster.setFromCamera(mouse, camera);
    const intersects = raycaster.intersectObjects(scene.children, true);

    let foundNode = null;
    for (let hit of intersects) {
      let obj = hit.object;
      while (obj && obj.parent && !obj.userData?.isNode) {
        obj = obj.parent;
      }
      if (obj && obj.userData?.isNode) {
        foundNode = nodes.get(obj.userData.id);
        break;
      }
    }

    if (foundNode !== hoveredNode) {
      if (hoveredNode && hoveredNode.id !== selectedNodeId) {
        hoveredNode.coreMesh.scale.set(1.0, 1.0, 1.0);
      }
      hoveredNode = foundNode;
      if (hoveredNode) {
        hoveredNode.coreMesh.scale.set(1.12, 1.12, 1.12);
      }
      container.style.cursor = hoveredNode ? 'pointer' : 'default';
    }
  }

  function onClick(event) {
    // Ignore clicks inside HUD elements
    if (
      event.target.closest('.hud-header') ||
      event.target.closest('.camera-toolbar') ||
      event.target.closest('.inspector-panel') ||
      event.target.closest('.modal-backdrop') ||
      event.target.closest('.toast')
    ) {
      return;
    }

    raycaster.setFromCamera(mouse, camera);
    const intersects = raycaster.intersectObjects(scene.children, true);

    let clickedNodeId = null;
    for (let hit of intersects) {
      let obj = hit.object;
      while (obj && obj.parent && !obj.userData?.isNode) {
        obj = obj.parent;
      }
      if (obj && obj.userData?.isNode) {
        clickedNodeId = obj.userData.id;
        break;
      }
    }

    if (clickedNodeId) {
      selectNode(clickedNodeId);
    }
  }

  // Keyboard shortcut listener (ESC to deselect)
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (!launchModal.classList.contains('hidden')) {
        launchModal.classList.add('hidden');
      } else {
        selectNode(null);
      }
    }
  });

  function onWindowResize() {
    const width = container.clientWidth || window.innerWidth;
    const height = container.clientHeight || window.innerHeight;
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    renderer.setSize(width, height);
  }

  // Animation Loop
  function animate() {
    requestAnimationFrame(animate);

    const delta = clock.getDelta();
    const time = clock.getElapsedTime();

    if (typeof TWEEN !== 'undefined' && TWEEN.update) {
      TWEEN.update();
    }

    if (controls) {
      if (isAutoRotating) {
        controls.autoRotate = true;
        controls.autoRotateSpeed = 1.2;
      } else {
        controls.autoRotate = false;
      }
      controls.update();
    }

    // Animate Particles
    if (particlesMesh) {
      const pos = particlesMesh.geometry.attributes.position.array;
      for (let i = 1; i < pos.length; i += 3) {
        pos[i] += delta * 1.5;
        if (pos[i] > 35) pos[i] = 0;
      }
      particlesMesh.geometry.attributes.position.needsUpdate = true;
    }

    // Animate All 3D Nodes
    nodes.forEach((node) => {
      node.animate(time, delta);
    });

    renderer.render(scene, camera);
  }

  // API Client Calls
  async function apiPost(url, data) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      });
      return await res.json();
    } catch (err) {
      showToast('Error de conexión: ' + err.message, 'error');
      return { success: false, message: err.message };
    }
  }

  async function fetchApps() {
    try {
      const res = await fetch('/api/apps');
      const data = await res.json();
      if (data.success && Array.isArray(data.data)) {
        syncApps(data.data);
      }
    } catch (e) {
      console.warn('[JVM 3D] Error fetching apps:', e);
    }
  }

  async function fetchStats() {
    try {
      const res = await fetch('/api/stats');
      const data = await res.json();
      if (data.success && data.data) {
        const stats = data.data;
        statRunning.textContent = stats.running_apps;
        statStopped.textContent = stats.stopped_apps;
        statMemory.textContent = stats.total_memory_str;
        statCpu.textContent = `${(stats.avg_cpu || 0).toFixed(1)}%`;
      }
    } catch (e) {
      console.warn('[JVM 3D] Error fetching stats:', e);
    }
  }

  // Connect SSE for Real-time Push
  function setupSSE() {
    if (eventSource) {
      eventSource.close();
    }
    eventSource = new EventSource('/api/stream');

    eventSource.addEventListener('apps', (event) => {
      try {
        const list = JSON.parse(event.data);
        syncApps(list);
      } catch (e) {
        console.error('Error parsing SSE apps:', e);
      }
    });

    eventSource.addEventListener('stats', (event) => {
      try {
        const stats = JSON.parse(event.data);
        statRunning.textContent = stats.running_apps;
        statStopped.textContent = stats.stopped_apps;
        statMemory.textContent = stats.total_memory_str;
        statCpu.textContent = `${(stats.avg_cpu || 0).toFixed(1)}%`;
      } catch (e) {
        console.error('Error parsing SSE stats:', e);
      }
    });

    eventSource.onerror = () => {
      setTimeout(setupSSE, 4000);
    };
  }

  // Toast Notifications
  function showToast(message, type = 'info') {
    const toastBox = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    toastBox.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(-10px)';
      toast.style.transition = 'all 0.3s';
      setTimeout(() => toast.remove(), 300);
    }, 3500);
  }

  // Action Button Handlers
  btnStopApp.addEventListener('click', async () => {
    if (!selectedNodeId) return;
    btnStopApp.disabled = true;
    showToast('Deteniendo aplicación...', 'info');

    const res = await apiPost('/api/apps/stop', { id: selectedNodeId, force: false });
    if (res.success) {
      showToast('Aplicación detenida correctamente', 'success');
      await fetchApps();
    } else {
      showToast('Error al detener: ' + res.message, 'error');
    }
    btnStopApp.disabled = false;
  });

  btnKillApp.addEventListener('click', async () => {
    if (!selectedNodeId) return;
    if (!confirm('¿Forzar terminación inmediata (SIGKILL)?')) return;
    showToast('Enviando SIGKILL...', 'error');

    const res = await apiPost('/api/apps/stop', { id: selectedNodeId, force: true });
    if (res.success) {
      showToast('Proceso terminado con SIGKILL', 'success');
      await fetchApps();
    } else {
      showToast('Error: ' + res.message, 'error');
    }
  });

  btnStartApp.addEventListener('click', async () => {
    if (!selectedNodeId) return;
    btnStartApp.disabled = true;
    showToast('Iniciando aplicación...', 'info');

    const res = await apiPost('/api/apps/start', { id: selectedNodeId });
    if (res.success) {
      showToast('¡Aplicación iniciada exitosamente!', 'success');
      await fetchApps();
    } else {
      showToast('Error al iniciar: ' + res.message, 'error');
    }
    btnStartApp.disabled = false;
  });

  btnRestartApp.addEventListener('click', async () => {
    if (!selectedNodeId) return;
    btnRestartApp.disabled = true;
    showToast('Reiniciando aplicación...', 'info');

    const res = await apiPost('/api/apps/restart', { id: selectedNodeId });
    if (res.success) {
      showToast('¡Aplicación reiniciada exitosamente!', 'success');
      await fetchApps();
    } else {
      showToast('Error al reiniciar: ' + res.message, 'error');
    }
    btnRestartApp.disabled = false;
  });

  btnCopyCmd.addEventListener('click', () => {
    const text = inspCmdLine.textContent;
    navigator.clipboard.writeText(text).then(() => {
      btnCopyCmd.textContent = '¡Copiado!';
      setTimeout(() => (btnCopyCmd.textContent = 'Copiar'), 2000);
    });
  });

  // Manual Refresh Button
  if (btnRefreshApps) {
    btnRefreshApps.addEventListener('click', async () => {
      showToast('Actualizando procesos Java...', 'info');
      await fetchApps();
      await fetchStats();
      showToast('Procesos actualizados', 'success');
    });
  }

  // Clear All Processes from Screen Button
  if (btnClearScreen) {
    btnClearScreen.addEventListener('click', async () => {
      btnClearScreen.disabled = true;
      selectNode(null);
      const res = await apiPost('/api/apps/clear', {});
      if (res.success) {
        syncApps([]);
        statRunning.textContent = '0';
        statStopped.textContent = '0';
        statMemory.textContent = '0 B';
        statCpu.textContent = '0.0%';
        showToast('Todos los procesos limpiados de la pantalla', 'success');
      } else {
        showToast('Error al limpiar pantalla: ' + res.message, 'error');
      }
      btnClearScreen.disabled = false;
    });
  }

  // Reset Everything Button
  if (btnResetAll) {
    btnResetAll.addEventListener('click', async () => {
      btnResetAll.disabled = true;
      showToast('Reseteando todo el monitor 3D...', 'info');

      // Reset UI state: filter, inspector, modal, auto-rotate, camera
      searchInput.value = '';
      currentFilter = '';
      selectNode(null);
      launchModal.classList.add('hidden');
      isAutoRotating = false;
      btnAutoRotate.classList.remove('active');
      camButtons.forEach((b) => b.classList.toggle('active', b.dataset.view === 'iso'));
      setCameraView('iso');

      const res = await apiPost('/api/apps/reset', {});
      if (res.success) {
        if (Array.isArray(res.data)) {
          syncApps(res.data);
        } else {
          await fetchApps();
        }
        await fetchStats();
        showToast('Monitor reseteado completamente', 'success');
      } else {
        showToast('Error al resetear: ' + res.message, 'error');
      }
      btnResetAll.disabled = false;
    });
  }

  // Spawn Demo App
  btnSpawnDemo.addEventListener('click', async () => {
    btnSpawnDemo.disabled = true;
    showToast('Compilando y lanzando microservicio Java de prueba...', 'info');

    const res = await apiPost('/api/demo/create', {});
    if (res.success && res.data) {
      showToast('¡App Java de prueba iniciada!', 'success');
      await fetchApps();
      if (res.data.id) {
        setTimeout(() => selectNode(res.data.id), 500);
      }
    } else {
      showToast('Error al crear demo: ' + res.message, 'error');
    }
    btnSpawnDemo.disabled = false;
  });

  // Launch Custom App Modal
  btnOpenLaunch.addEventListener('click', () => {
    launchModal.classList.remove('hidden');
  });

  btnCloseModal.addEventListener('click', () => {
    launchModal.classList.add('hidden');
  });

  btnCancelModal.addEventListener('click', () => {
    launchModal.classList.add('hidden');
  });

  launchForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = document.getElementById('launch-name').value;
    const command = document.getElementById('launch-cmd').value;
    const cwd = document.getElementById('launch-cwd').value;

    showToast('Ejecutando comando Java...', 'info');
    const res = await apiPost('/api/apps/launch', { name, command, cwd });
    if (res.success && res.data) {
      showToast('¡Proceso lanzado exitosamente!', 'success');
      launchModal.classList.add('hidden');
      launchForm.reset();
      await fetchApps();
      if (res.data.id) {
        setTimeout(() => selectNode(res.data.id), 500);
      }
    } else {
      showToast('Error al lanzar: ' + res.message, 'error');
    }
  });

  // Close Inspector
  btnCloseInspector.addEventListener('click', () => {
    selectNode(null);
  });

  // Search filter
  searchInput.addEventListener('input', (e) => {
    currentFilter = e.target.value.trim().toLowerCase();
  });

  // Camera toolbar
  camButtons.forEach((btn) => {
    btn.addEventListener('click', () => {
      camButtons.forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      setCameraView(btn.dataset.view);
    });
  });

  btnAutoRotate.addEventListener('click', () => {
    isAutoRotating = !isAutoRotating;
    btnAutoRotate.classList.toggle('active', isAutoRotating);
  });

  btnResetCam.addEventListener('click', () => {
    setCameraView('iso');
  });

  // Format utilities
  function formatBytes(b) {
    if (!b || b === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(b) / Math.log(k));
    return parseFloat((b / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function formatUptime(seconds) {
    if (!seconds || seconds <= 0) return '--';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    if (h > 0) return `${h}h ${m}m ${s}s`;
    if (m > 0) return `${m}m ${s}s`;
    return `${s}s`;
  }

  // Bootstrap Application
  function start() {
    init3D();
    fetchApps();
    fetchStats();
    setupSSE();

    // Polling sync every 2 seconds for guaranteed freshness
    setInterval(() => {
      fetchApps();
      fetchStats();
    }, 2000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
