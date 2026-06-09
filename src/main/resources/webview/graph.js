let graph = null;
let currentData = { nodes: [], links: [] };
let highlightedNodeId = null;
let neighborIndex = {};

const EXT_COLORS = {
    'java':   '#61afef',
    'kt':     '#c678dd',
    'kts':    '#c678dd',
    'xml':    '#e5c07b',
    'json':   '#e5c07b',
    'yaml':   '#56b6c2',
    'yml':    '#56b6c2',
    'html':   '#d19a66',
    'css':    '#d19a66',
    'js':     '#e5c07b',
    'ts':     '#56b6c2',
    'gradle': '#98c379',
    'md':     '#98c379',
    'py':     '#61afef',
    'rs':     '#dea584',
    'go':     '#00ADD8'
};

function getColor(node) {
    if (node.isCircular) return '#be5046';
    const ext = node.name.split('.').pop().toLowerCase();
    return EXT_COLORS[ext] || '#abb2bf';
}

/* ---------- neighbor index ---------- */
function buildNeighborIndex(data) {
    const map = {};
    data.links.forEach(l => {
        const s = typeof l.source === 'object' ? l.source.id : l.source;
        const t = typeof l.target === 'object' ? l.target.id : l.target;
        (map[s] ??= new Set()).add(t);
        (map[t] ??= new Set()).add(s);
    });
    return map;
}
function updateNeighborIndex() {
    neighborIndex = buildNeighborIndex(currentData);
}

/* ---------- tooltip ---------- */
const tooltip = document.getElementById('tooltip');

function showTooltip(node, ev) {
    const connections = neighborIndex[node.id]?.size ?? 0;
    tooltip.innerHTML = `
        <div class="tooltip-name">${node.name}</div>
        <div class="tooltip-path">${node.directory || ''}</div>
        <div class="tooltip-connections">${connections} connection${connections !== 1 ? 's' : ''}</div>
        ${node.isCircular ? '<div style="color:#f44747;margin-top:4px">⚠ Circular dependency</div>' : ''}
    `;
    tooltip.style.display = 'block';
    tooltip.style.left = (ev.clientX + 15) + 'px';
    tooltip.style.top = (ev.clientY + 15) + 'px';
}
function hideTooltip() {
    tooltip.style.display = 'none';
}

/* ---------- init graph ---------- */
function initGraph() {
    const container = document.getElementById('graph-container');

    graph = ForceGraph()(container)
        .width(window.innerWidth)
        .height(window.innerHeight)

        .nodeId('id')
        .nodeLabel(null)
        .nodeVal(node => 3 + (node.connections || 0) * 1.5)
        .nodeRelSize(4)

        .nodeCanvasObject((node, ctx, globalScale) => {
            const isHighlighted = highlightedNodeId === node.id;
            const isNeighbor = highlightedNodeId && neighborIndex[highlightedNodeId]?.has(node.id);
            const isDimmed = highlightedNodeId && !isHighlighted && !isNeighbor;

            const size = 3 + (node.connections || 0) * 1.2;
            const color = getColor(node);

            ctx.beginPath();
            ctx.arc(node.x, node.y, size, 0, 2 * Math.PI);
            ctx.fillStyle = isDimmed ? 'rgba(80,80,80,0.25)' :
                isHighlighted ? '#ffffff' : color;
            if (isHighlighted) {
                ctx.shadowColor = color;
                ctx.shadowBlur = 15;
            }
            ctx.fill();
            ctx.shadowBlur = 0;

            if (isHighlighted) {
                ctx.strokeStyle = color;
                ctx.lineWidth = 2;
                ctx.stroke();
            }

            if (globalScale > 1.2 || isHighlighted || isNeighbor) {
                const label = node.name;
                const fontSize = Math.max(10 / globalScale, 8);
                ctx.font = `${fontSize}px system-ui, sans-serif`;
                ctx.textAlign = 'center';
                ctx.textBaseline = 'top';
                ctx.fillStyle = isDimmed ? 'rgba(100,100,100,0.3)' :
                    isHighlighted ? '#ffffff' : 'rgba(200,200,200,0.85)';
                ctx.fillText(label, node.x, node.y + size + 2);
            }
        })

        .linkSource('source')
        .linkTarget('target')
        .linkColor(link => {
            if (link.isCircular) return 'rgba(190,80,70,0.6)';
            if (highlightedNodeId) {
                const s = typeof link.source === 'object' ? link.source.id : link.source;
                const t = typeof link.target === 'object' ? link.target.id : link.target;
                return (s === highlightedNodeId || t === highlightedNodeId) ?
                    'rgba(255,255,255,0.5)' : 'rgba(255,255,255,0.02)';
            }
            return 'rgba(255,255,255,0.1)';
        })
        .linkWidth(l => l.isCircular ? 2 : 0.8)
        .linkDirectionalArrowLength(4)
        .linkDirectionalArrowRelPos(1)
        .linkDirectionalArrowColor(l => l.isCircular ? 'rgba(190,80,70,0.8)' : 'rgba(255,255,255,0.15)')

        .onNodeClick(handleNodeClick)
        .onNodeHover(handleNodeHover)
        .onBackgroundClick(() => {
            highlightedNodeId = null;
            graph.refresh();
        })

        .d3AlphaDecay(0.02)
        .d3VelocityDecay(0.3)
        .warmupTicks(100)
        .cooldownTicks(300)

        .backgroundColor('#1e1e1e');

    /* ---- плавный зум колесиком / жестами ---- */
    graph.onWheel(event => {
        event.preventDefault();
        const direction = Math.sign(event.deltaY);
        const factor = direction < 0 ? 1.15 : 0.85;
        const newZoom = Math.min(Math.max(graph.zoom() * factor, 0.2), 5);
        graph.zoom(newZoom, 200);
    });

    window.addEventListener('resize', () => {
        graph.width(window.innerWidth).height(window.innerHeight);
    });
}

/* ---------- двойной клик ---------- */
let lastClickTime = 0;
let lastClickedNodeId = null;
const DOUBLE_CLICK_MS = 300;

function handleNodeClick(node) {
    if (!node) return;
    const now = Date.now();

    if (lastClickedNodeId === node.id && now - lastClickTime < DOUBLE_CLICK_MS) {
        if (window.openFileInIDE) window.openFileInIDE(node.path);
        lastClickTime = 0;
        lastClickedNodeId = null;
        return;
    }

    highlightedNodeId = (highlightedNodeId === node.id) ? null : node.id;
    graph.refresh();

    lastClickTime = now;
    lastClickedNodeId = node.id;
}

/* ---------- hover ---------- */
function handleNodeHover(node, prevNode) {
    document.body.style.cursor = node ? 'pointer' : 'default';
    if (node) {
        const rect = document.getElementById('graph-container').getBoundingClientRect();
        const screen = graph.graph2ScreenCoords(node.x, node.y);
        showTooltip(node, { clientX: screen.x + rect.left, clientY: screen.y + rect.top });
    } else hideTooltip();
}

/* ---------- bridge API ---------- */
window.updateGraph = function (jsonString) {
    try {
        const data = JSON.parse(jsonString);
        currentData = data;
        highlightedNodeId = null;
        updateNeighborIndex();
        graph.graphData(currentData);
        updateStatus(currentData);
        setTimeout(() => graph.zoomToFit(400, 40), 1500);
    } catch (e) {
        console.error(e);
        const s = document.getElementById('status');
        s.textContent = '❌ Error';
        s.style.color = '#f44747';
    }
};

window.highlightNodeById = function (nodeId) {
    highlightedNodeId = nodeId;
    graph.refresh();
    const n = currentData.nodes.find(v => v.id === nodeId);
    if (n) {
        graph.centerAt(n.x, n.y, 500);
        graph.zoom(3, 500);
    }
};

window.onBridgeReady = function () {
    console.log('Bridge ready');
};

/* ---------- статус-бар ---------- */
function updateStatus(data) {
    const status = document.getElementById('status');
    const nodeCnt = document.getElementById('node-count');
    const edgeCnt = document.getElementById('edge-count');
    const circ = document.getElementById('circular-warn');

    status.textContent = '● Connected';
    status.style.color = '#4ec9b0';
    nodeCnt.textContent = `${data.nodes.length} files`;
    edgeCnt.textContent = `${data.links.length} deps`;

    const circCnt = data.links.filter(l => l.isCircular).length;
    circ.textContent = circCnt ? `⚠ ${circCnt} circular` : '';
}

/* ---------- тема ---------- */
function applyTheme() {
    const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.body.style.background = dark ? '#1e1e1e' : '#ffffff';
}
applyTheme();
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

/* ---------- кнопки зума ---------- */
document.getElementById('zoom-in')?.addEventListener('click', () => graph.zoom(graph.zoom() * 1.25, 300));
document.getElementById('zoom-out')?.addEventListener('click', () => graph.zoom(graph.zoom() * 0.8, 300));
document.getElementById('reset-zoom')?.addEventListener('click', () => graph.zoomToFit(400, 40));

/* ---------- старт ---------- */
document.addEventListener('DOMContentLoaded', initGraph);
