package io.jettra.store.explorer.pages.map;

import io.jettra.flux.core.FluxView;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Badge;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Header;
import io.jettra.flux.widgets.JettraButton;
import io.jettra.flux.widgets.Link;
import io.jettra.flux.widgets.Paragraph;
import io.jettra.flux.widgets.RawScript;
import io.jettra.flux.widgets.Row;
import io.jettra.flux.widgets.Span;
import io.jettra.store.explorer.model.ServerNodeInfo;
import io.jettra.store.explorer.pages.template.FluxUi;
import io.jettra.store.explorer.plugin.JettraStorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vista JettraFlux para el Mapa Gráfico de Transferencia de Datos en Tiempo Real (/map).
 * Visualiza los nodos del clúster, su estado (ACTIVE/INACTIVE, PRIMARY/SECONDARY),
 * enlaces de replicación Raft y paquetes de datos transmitidos en tiempo real.
 */
public class TransferMapFluxView extends FluxView {

    public TransferMapFluxView() {
    }

    @Override
    public Widget build() {
        JettraStorePlugin plugin = JettraStorePlugin.getInstance();
        plugin.refreshAllNodeStatusesIfStale(300L);

        List<ServerNodeInfo> nodes = plugin.getServerNodes();
        ServerNodeInfo primary = plugin.getPrimaryNode();
        long activeCount = nodes.stream().filter(ServerNodeInfo::isActive).count();
        long activeSecondaries = nodes.stream().filter(n -> n != primary && n.isActive()).count();
        long totalQps = nodes.stream().filter(ServerNodeInfo::isActive).mapToLong(ServerNodeInfo::getQps).sum();
        String primaryLabel = primary != null ? primary.getId() : "N/A";

        // 1. KPIs superiores
        Widget kpiGrid = Div.of(
                buildKpiCard("Nodos Activos", activeCount + " / " + nodes.size(), "map-kpi-active", "#10b981"),
                buildKpiCard("Nodo Maestro (PRIMARY)", primaryLabel, "map-kpi-primary", "#ffd700"),
                buildKpiCard("Enlaces de Replicación", activeSecondaries + " activos", "map-kpi-links", "#38bdf8"),
                buildKpiCard("Flujo Total (QPS)", String.format(Locale.US, "%,d ops/s", totalQps), "map-kpi-qps", "#a78bfa")
        ).modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1rem; margin-bottom: 1.25rem;"));

        // 2. Cabecera del Mapa Gráfico + Controles
        Header mapTitle = Header.of(3, "🗺️ Mapa de Transferencia y Replicación en Tiempo Real");
        mapTitle.modifier(new Modifier().style("margin: 0; font-size: 1.1rem; color: var(--text-main);"));

        Badge liveBadge = Badge.of("● LIVE STREAM");
        liveBadge.id("map-live-badge");
        liveBadge.modifier(new Modifier()
                .attribute("id", "map-live-badge")
                .style("background: rgba(16, 185, 129, 0.2); border: 1px solid #10b981; color: #10b981; font-size: 0.72rem; padding: 0.2rem 0.6rem; border-radius: 9999px; font-weight: 700;"));

        JettraButton toggleAnimBtn = JettraButton.of("⏸️ Pausar Flujo").severity(JettraButton.Severity.SECONDARY);
        toggleAnimBtn.id("map-toggle-anim-btn");
        toggleAnimBtn.modifier(new Modifier()
                .attribute("id", "map-toggle-anim-btn")
                .cssClass("app-btn-secondary")
                .attribute("onclick", "window.jettraTransferMap && window.jettraTransferMap.toggleAnimation()"));

        JettraButton refreshBtn = JettraButton.of("🔄 Sincronizar Ahora").severity(JettraButton.Severity.PRIMARY);
        refreshBtn.id("map-refresh-btn");
        refreshBtn.modifier(new Modifier()
                .attribute("id", "map-refresh-btn")
                .cssClass("app-btn-primary")
                .attribute("onclick", "window.jettraTransferMap && window.jettraTransferMap.refreshNow()"));

        Link clusterLink = FluxUi.linkButton("/cluster", "🌐 Administrar Nodos", "app-btn-secondary", "display: inline-flex; align-items: center;");

        Row mapControls = Row.of(liveBadge, toggleAnimBtn, refreshBtn, clusterLink);
        mapControls.modifier(new Modifier().style("display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap;"));

        Row mapHeader = Row.of(mapTitle, mapControls);
        mapHeader.modifier(new Modifier().style("display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 0.85rem;"));

        // 3. Leyenda visual
        Row legend = Row.of(
                legendItem("#ffd700", "PRIMARY / LEADER"),
                legendItem("#10b981", "SECONDARY ACTIVE"),
                legendItem("#ef4444", "OFFLINE / INACTIVE"),
                legendItem("#38bdf8", "Heartbeat / Sync Raft"),
                legendItem("#c084fc", "Transferencia de Datos / Objetos")
        );
        legend.modifier(new Modifier().style("display: flex; gap: 1rem; flex-wrap: wrap; align-items: center; margin-bottom: 0.85rem; font-size: 0.78rem; color: var(--text-muted);"));

        // 4. Lienzo gráfico interactivo (SVG generado dinámicamente por JS sobre este contenedor JettraFlux)
        Div svgHost = Div.of();
        svgHost.id("transfer-map-canvas-host");
        svgHost.modifier(new Modifier()
                .attribute("id", "transfer-map-canvas-host")
                .style("width: 100%; min-height: 430px; height: 460px; border-radius: 12px; border: 1px solid var(--border-color); background: radial-gradient(circle at 50% 35%, rgba(56, 189, 248, 0.08), rgba(15, 23, 42, 0.92)); position: relative; overflow: hidden;"));

        // Panel de inspección del nodo seleccionado
        Paragraph inspectorText = Paragraph.of("Haz clic sobre cualquier nodo en el mapa para inspeccionar su telemetría y flujo de replicación.");
        inspectorText.id("map-node-inspector");
        inspectorText.modifier(new Modifier()
                .attribute("id", "map-node-inspector")
                .style("margin: 0.75rem 0 0 0; font-size: 0.83rem; color: var(--text-muted); padding: 0.65rem 0.85rem; border-radius: 8px; background: var(--bg-subtle); border: 1px solid var(--border-color);"));

        Div mapCard = Div.of(mapHeader, legend, svgHost, inspectorText);
        mapCard.modifier(new Modifier().cssClass("ui-card").style("margin-bottom: 1.25rem;"));

        // 5. Bitácora en tiempo real de transmisiones y eventos del clúster
        Header feedTitle = Header.of(4, "📡 Transmisiones y Eventos de Replicación en Tiempo Real");
        feedTitle.modifier(new Modifier().style("margin: 0 0 0.75rem 0; font-size: 1rem; color: var(--text-main);"));

        Div feedList = Div.of(
                Paragraph.of("Esperando eventos de replicación y transferencia del clúster...")
                        .modifier(new Modifier().style("margin: 0; color: var(--text-muted); font-size: 0.82rem;"))
        );
        feedList.id("map-transfer-feed");
        feedList.modifier(new Modifier()
                .attribute("id", "map-transfer-feed")
                .style("display: flex; flex-direction: column; gap: 0.45rem; max-height: 260px; overflow-y: auto;"));

        Div feedCard = Div.of(feedTitle, feedList);
        feedCard.modifier(new Modifier().cssClass("ui-card"));

        RawScript liveScript = RawScript.of(buildTransferMapScript());

        List<Widget> children = new ArrayList<>();
        children.add(kpiGrid);
        children.add(mapCard);
        children.add(feedCard);
        children.add(liveScript);

        return Div.of(children.toArray(new Widget[0]));
    }

    private Widget buildKpiCard(String label, String value, String valueId, String accentColor) {
        Span lbl = Span.of(label);
        lbl.modifier(new Modifier().style("font-size: 0.78rem; color: var(--text-muted); font-weight: 600; display: block; margin-bottom: 0.35rem;"));

        Span val = Span.of(value);
        val.id(valueId);
        val.modifier(new Modifier()
                .attribute("id", valueId)
                .style("font-size: 1.4rem; font-weight: 800; color: " + accentColor + "; display: block;"));

        Div card = Div.of(lbl, val);
        card.modifier(new Modifier().cssClass("ui-card").style("border-left: 4px solid " + accentColor + "; padding: 0.9rem 1rem;"));
        return card;
    }

    private Widget legendItem(String color, String text) {
        Span dot = Span.of("● ");
        dot.modifier(new Modifier().style("color: " + color + "; font-size: 0.95rem;"));
        Span txt = Span.of(text);
        return Span.of(dot, txt);
    }

    private static String buildTransferMapScript() {
        return """
        (function() {
          const host = document.getElementById('transfer-map-canvas-host');
          if (!host) return;

          const canvas = document.createElement('canvas');
          canvas.style.width = '100%';
          canvas.style.height = '100%';
          canvas.style.display = 'block';
          host.appendChild(canvas);
          const ctx = canvas.getContext('2d');

          let clusterState = { nodes: [], recentTransfers: [], primaryNodeId: '' };
          let nodePositions = [];
          let particles = [];
          let animating = true;
          let selectedNodeId = null;
          let lastEventKey = '';
          let frameTick = 0;

          function resizeCanvas() {
            const rect = host.getBoundingClientRect();
            const dpr = window.devicePixelRatio || 1;
            canvas.width = Math.max(320, Math.floor(rect.width * dpr));
            canvas.height = Math.max(320, Math.floor(rect.height * dpr));
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            computeLayout();
          }

          function isPrimaryNode(n) {
            if (!n) return false;
            const r = (n.role || '').toUpperCase();
            return n.id === clusterState.primaryNodeId || r === 'PRIMARY' || r === 'LEADER';
          }

          function computeLayout() {
            const rect = host.getBoundingClientRect();
            const w = Math.max(320, rect.width);
            const h = Math.max(320, rect.height);
            const nodes = clusterState.nodes || [];
            nodePositions = [];
            if (nodes.length === 0) return;

            const primary = nodes.find(isPrimaryNode) || nodes[0];
            const others = nodes.filter(n => n.id !== primary.id);

            const cx = w * 0.5;
            const cy = others.length <= 2 ? h * 0.30 : h * 0.48;
            nodePositions.push({ node: primary, x: cx, y: cy, radius: 36, isPrimary: true });

            if (others.length > 0) {
              const rx = Math.min(w * 0.34, 290);
              const ry = Math.min(h * 0.34, 155);
              others.forEach((n, idx) => {
                let x, y;
                if (others.length === 1) {
                  x = cx;
                  y = h * 0.74;
                } else if (others.length === 2) {
                  x = cx + (idx === 0 ? -rx * 0.85 : rx * 0.85);
                  y = h * 0.72;
                } else {
                  const angle = (Math.PI * 2 * idx) / others.length - Math.PI / 2;
                  x = cx + Math.cos(angle) * rx;
                  y = cy + Math.sin(angle) * ry;
                }
                nodePositions.push({ node: n, x, y, radius: 29, isPrimary: false });
              });
            }
          }

          function spawnPacket(fromPos, toPos, color, label, speed) {
            particles.push({
              x0: fromPos.x, y0: fromPos.y,
              x1: toPos.x, y1: toPos.y,
              t: 0,
              speed: speed || (0.012 + Math.random() * 0.008),
              color: color || '#38bdf8',
              label: label || ''
            });
            if (particles.length > 80) particles.shift();
          }

          function updateKpis(data) {
            const kActive = document.getElementById('map-kpi-active');
            const kPrimary = document.getElementById('map-kpi-primary');
            const kLinks = document.getElementById('map-kpi-links');
            const kQps = document.getElementById('map-kpi-qps');
            const nodes = data.nodes || [];
            const activeSecondaries = nodes.filter(n => n.id !== data.primaryNodeId && n.active).length;

            if (kActive) kActive.textContent = (data.activeCount || 0) + ' / ' + (data.totalCount || nodes.length);
            if (kPrimary) kPrimary.textContent = data.primaryNodeId || 'N/A';
            if (kLinks) kLinks.textContent = activeSecondaries + ' activos';
            if (kQps) kQps.textContent = Number(data.totalQps || 0).toLocaleString('en-US') + ' ops/s';
          }

          function updateFeed(transfers) {
            const feed = document.getElementById('map-transfer-feed');
            if (!feed) return;
            if (!transfers || transfers.length === 0) {
              feed.innerHTML = '<p style="margin:0;color:var(--text-muted);font-size:0.82rem;">Sin transferencias recientes registradas aún. Las replicaciones Raft y sincronizaciones aparecerán aquí en tiempo real.</p>';
              return;
            }
            const newest = transfers[0];
            const newestKey = (newest.timestamp || '') + '|' + (newest.type || '') + '|' + (newest.message || '');
            if (newestKey !== lastEventKey) {
              lastEventKey = newestKey;
              const primaryPos = nodePositions.find(p => p.isPrimary);
              if (primaryPos) {
                nodePositions.filter(p => !p.isPrimary && p.node.active).forEach(secPos => {
                  spawnPacket(primaryPos, secPos, '#c084fc', newest.type || 'DATA', 0.018);
                });
              }
            }
            feed.innerHTML = transfers.slice(0, 15).map(ev => {
              const type = ev.type || 'EVENT';
              const isData = type.includes('TRANSFER') || type.includes('DATABASE') || type.includes('DOCUMENT') || type.includes('ENGINE') || type.includes('INDEX') || type.includes('RECORD');
              const badgeColor = isData ? '#c084fc' : (type.includes('OFFLINE') || type.includes('STOPPED') ? '#ef4444' : '#10b981');
              return '<div style="display:flex;justify-content:space-between;align-items:center;gap:0.75rem;padding:0.45rem 0.65rem;border-radius:8px;background:var(--bg-subtle);border:1px solid var(--border-color);font-size:0.79rem;">'
                + '<div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap;">'
                + '<span style="background:rgba(192,132,252,0.15);color:' + badgeColor + ';border:1px solid ' + badgeColor + ';padding:0.1rem 0.45rem;border-radius:9999px;font-weight:700;font-size:0.7rem;">' + type + '</span>'
                + '<strong style="color:var(--text-main);">' + (ev.source || 'cluster') + ' → ' + (ev.target || 'cluster') + '</strong>'
                + '<span style="color:var(--text-muted);">' + (ev.message || '') + '</span>'
                + '</div>'
                + '<span style="color:var(--text-muted);font-size:0.72rem;white-space:nowrap;">' + (ev.time || '') + '</span>'
                + '</div>';
            }).join('');
          }

          function updateInspector() {
            const el = document.getElementById('map-node-inspector');
            if (!el || !selectedNodeId) return;
            const found = (clusterState.nodes || []).find(n => n.id === selectedNodeId);
            if (!found) return;
            const stateTxt = found.active ? '🟢 ACTIVE (' + found.latencyMs + ' ms)' : '🔴 INACTIVE / OFFLINE';
            el.innerHTML = '<strong>🖥️ ' + found.id + '</strong> (' + found.role + ') — ' + stateTxt
              + ' | <strong>Endpoint:</strong> ' + found.host + ':' + found.port
              + ' | <strong>Raft:</strong> ' + (found.raftState || 'N/A') + ' (Term #' + found.raftTerm + ')'
              + ' | <strong>QPS:</strong> ' + Number(found.qps || 0).toLocaleString('en-US') + ' ops/s'
              + ' | <strong>CPU:</strong> ' + found.cpuPercent + '%'
              + ' | <strong>Heap:</strong> ' + found.heapUsedMb + '/' + found.heapMaxMb + ' MB';
          }

          function draw() {
            const rect = host.getBoundingClientRect();
            const w = Math.max(320, rect.width);
            const h = Math.max(320, rect.height);
            ctx.clearRect(0, 0, w, h);

            // Cuadrícula sutil de fondo
            ctx.strokeStyle = 'rgba(148, 163, 184, 0.07)';
            ctx.lineWidth = 1;
            for (let gx = 40; gx < w; gx += 40) {
              ctx.beginPath(); ctx.moveTo(gx, 0); ctx.lineTo(gx, h); ctx.stroke();
            }
            for (let gy = 40; gy < h; gy += 40) {
              ctx.beginPath(); ctx.moveTo(0, gy); ctx.lineTo(w, gy); ctx.stroke();
            }

            const primaryPos = nodePositions.find(p => p.isPrimary);

            // 1. Enlaces de replicación desde PRIMARY hacia cada SECONDARY
            if (primaryPos) {
              nodePositions.filter(p => !p.isPrimary).forEach(secPos => {
                const linkActive = primaryPos.node.active && secPos.node.active;
                ctx.save();
                ctx.beginPath();
                ctx.moveTo(primaryPos.x, primaryPos.y);
                ctx.lineTo(secPos.x, secPos.y);
                if (linkActive) {
                  ctx.strokeStyle = 'rgba(56, 189, 248, 0.55)';
                  ctx.lineWidth = 2.5;
                } else {
                  ctx.setLineDash([6, 6]);
                  ctx.strokeStyle = 'rgba(239, 68, 68, 0.55)';
                  ctx.lineWidth = 2;
                }
                ctx.stroke();
                ctx.restore();

                // Etiqueta de latencia en el punto medio
                const mx = (primaryPos.x + secPos.x) * 0.5;
                const my = (primaryPos.y + secPos.y) * 0.5;
                const labelText = linkActive ? ('⚡ ' + secPos.node.latencyMs + ' ms') : '✕ OFFLINE';
                ctx.font = '600 11px system-ui, -apple-system, sans-serif';
                const tw = ctx.measureText(labelText).width + 14;
                ctx.fillStyle = linkActive ? 'rgba(15, 23, 42, 0.88)' : 'rgba(127, 29, 29, 0.88)';
                ctx.fillRect(mx - tw / 2, my - 10, tw, 20);
                ctx.strokeStyle = linkActive ? '#38bdf8' : '#ef4444';
                ctx.lineWidth = 1;
                ctx.strokeRect(mx - tw / 2, my - 10, tw, 20);
                ctx.fillStyle = linkActive ? '#bae6fd' : '#fecaca';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(labelText, mx, my);

                // Generar pulsos continuos de heartbeat / transferencia en enlaces activos
                if (animating && linkActive && frameTick % 55 === 0) {
                  spawnPacket(primaryPos, secPos, '#38bdf8', 'RAFT', 0.011);
                }
                if (animating && linkActive && secPos.node.qps > 0 && frameTick % 95 === 0) {
                  spawnPacket(primaryPos, secPos, '#c084fc', 'SYNC', 0.014);
                }
              });
            }

            // 2. Paquetes animados en tránsito
            for (let i = particles.length - 1; i >= 0; i--) {
              const p = particles[i];
              if (animating) p.t += p.speed;
              if (p.t >= 1) {
                particles.splice(i, 1);
                continue;
              }
              const px = p.x0 + (p.x1 - p.x0) * p.t;
              const py = p.y0 + (p.y1 - p.y0) * p.t;
              ctx.save();
              ctx.beginPath();
              ctx.arc(px, py, 5.5, 0, Math.PI * 2);
              ctx.fillStyle = p.color;
              ctx.shadowColor = p.color;
              ctx.shadowBlur = 12;
              ctx.fill();
              ctx.restore();
            }

            // 3. Dibujar nodos
            nodePositions.forEach(pos => {
              const n = pos.node;
              const active = !!n.active;
              const strokeColor = !active ? '#ef4444' : (pos.isPrimary ? '#ffd700' : '#10b981');
              const fillColor = !active ? 'rgba(127, 29, 29, 0.78)' : (pos.isPrimary ? 'rgba(113, 63, 18, 0.85)' : 'rgba(6, 78, 59, 0.82)');

              // Halo de pulso para nodos activos
              if (active) {
                const pulse = 5 + Math.sin(frameTick * 0.06) * 3;
                ctx.beginPath();
                ctx.arc(pos.x, pos.y, pos.radius + pulse, 0, Math.PI * 2);
                ctx.strokeStyle = pos.isPrimary ? 'rgba(255, 215, 0, 0.25)' : 'rgba(16, 185, 129, 0.25)';
                ctx.lineWidth = 3;
                ctx.stroke();
              }

              // Círculo principal del nodo
              ctx.beginPath();
              ctx.arc(pos.x, pos.y, pos.radius, 0, Math.PI * 2);
              ctx.fillStyle = fillColor;
              ctx.fill();
              ctx.lineWidth = selectedNodeId === n.id ? 4 : 2.5;
              ctx.strokeStyle = strokeColor;
              ctx.stroke();

              // Texto dentro del nodo (ID y Rol)
              ctx.fillStyle = '#ffffff';
              ctx.font = '700 12px system-ui, -apple-system, sans-serif';
              ctx.textAlign = 'center';
              ctx.textBaseline = 'middle';
              ctx.fillText(n.id, pos.x, pos.y - 6);

              ctx.fillStyle = strokeColor;
              ctx.font = '700 9.5px system-ui, -apple-system, sans-serif';
              ctx.fillText(pos.isPrimary ? 'PRIMARY' : (n.role || 'SECONDARY'), pos.x, pos.y + 9);

              // Etiqueta inferior (host:port + estado)
              const subText = n.host + ':' + n.port + ' • ' + (active ? 'ONLINE' : 'OFFLINE');
              ctx.fillStyle = active ? '#e2e8f0' : '#fca5a5';
              ctx.font = '600 11px system-ui, -apple-system, sans-serif';
              ctx.fillText(subText, pos.x, pos.y + pos.radius + 16);
            });

            if (animating) frameTick++;
            requestAnimationFrame(draw);
          }

          canvas.addEventListener('click', (ev) => {
            const rect = canvas.getBoundingClientRect();
            const mx = ev.clientX - rect.left;
            const my = ev.clientY - rect.top;
            for (const pos of nodePositions) {
              const dist = Math.hypot(mx - pos.x, my - pos.y);
              if (dist <= pos.radius + 8) {
                selectedNodeId = pos.node.id;
                updateInspector();
                break;
              }
            }
          });

          function applyMapData(data) {
            if (!data) return;
            clusterState = data;
            if (selectedNodeId && Array.isArray(data.nodes) && !data.nodes.some(n => n.id === selectedNodeId)) {
              selectedNodeId = null;
            }
            computeLayout();
            updateKpis(data);
            updateFeed(data.recentTransfers || []);
            updateInspector();
          }

          async function fetchState() {
            try {
              const res = await fetch('/api/cluster/status', { cache: 'no-store' });
              if (!res.ok) return;
              const data = await res.json();
              applyMapData(data);
            } catch (e) {}
          }

          // 1. Conexión reactiva en tiempo real mediante Server-Sent Events (SSE)
          if (window.EventSource) {
            try {
              const sse = new EventSource('/api/cluster/events');
              sse.addEventListener('cluster_status', function(e) {
                try {
                  const data = JSON.parse(e.data);
                  applyMapData(data);
                } catch (err) {}
              });
            } catch (e) {}
          }

          window.jettraTransferMap = {
            toggleAnimation: function() {
              animating = !animating;
              const btn = document.getElementById('map-toggle-anim-btn');
              if (btn) btn.textContent = animating ? '⏸️ Pausar Flujo' : '▶️ Reanudar Flujo';
            },
            refreshNow: function() {
              fetchState();
            }
          };

          window.addEventListener('resize', resizeCanvas);
          resizeCanvas();
          fetchState();
          requestAnimationFrame(draw);
          setInterval(fetchState, 1000);
        })();
        """;
    }
}
