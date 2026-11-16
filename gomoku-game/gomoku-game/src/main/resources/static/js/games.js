// ============================================================
// 游戏渲染与规则模块 — GameModules[GOMOKU|REVERSI|GO9]
//
// 每个模块提供：
//   rows/cols        棋盘尺寸
//   stepTime/total   计时默认值（与服务端引擎一致）
//   footer           大厅/棋盘页脚说明
//   canvasSize()     画布像素尺寸
//   draw(ctx, board, opts)          渲染（opts: lastMove/winLine/myStone/currentTurn/hint)
//   hitTest(mx, my)                 像素 → {row, col} | null
//   drawGhost(ctx, row, col, side)  落子预览
//   applyEffects(board, effects)    应用服务端效果（提子/翻转），返回自动停一手提示或 null
//   isLegal(board, row, col, side)  本地合法手粗校验（服务端为权威）
//   legalMoves(board, side)         合法手列表（用于提示，可为空实现）
// ============================================================
(function () {
  'use strict';

  const BLACK = 1, WHITE = 2, EMPTY = 0;

  function emptyBoard(rows, cols) {
    return Array.from({ length: rows }, () => new Array(cols).fill(EMPTY));
  }

  // ---------- 公共棋子绘制（交叉点类：五子棋/围棋） ----------
  function drawStoneAt(ctx, x, y, r, side, isLast, isWin) {
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,0.5)';
    ctx.shadowBlur = 8;
    ctx.shadowOffsetX = 2;
    ctx.shadowOffsetY = 3;

    const grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
    if (side === BLACK) {
      grad.addColorStop(0, '#666');
      grad.addColorStop(0.4, '#222');
      grad.addColorStop(1, '#0a0a0a');
    } else {
      grad.addColorStop(0, '#ffffff');
      grad.addColorStop(0.5, '#eeebe4');
      grad.addColorStop(1, '#c8c4bc');
    }
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowColor = 'transparent';

    // 高光
    const hi = ctx.createRadialGradient(x - r * 0.35, y - r * 0.35, 0, x, y, r * 0.8);
    hi.addColorStop(0, side === BLACK ? 'rgba(255,255,255,0.2)' : 'rgba(255,255,255,0.7)');
    hi.addColorStop(1, 'transparent');
    ctx.fillStyle = hi;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();

    // 最后落子标记
    if (isLast) {
      ctx.fillStyle = side === BLACK ? 'rgba(255,80,80,0.9)' : 'rgba(220,60,60,0.9)';
      ctx.beginPath();
      ctx.arc(x, y, r * 0.25, 0, Math.PI * 2);
      ctx.fill();
    }

    // 获胜连线高亮
    if (isWin) {
      ctx.strokeStyle = 'rgba(255,60,60,0.9)';
      ctx.lineWidth = 3;
      ctx.shadowColor = 'rgba(255,60,60,0.8)';
      ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(x, y, r + 3, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
  }

  // ---------- 木纹棋盘背景（交叉点类共用） ----------
  function drawWood(ctx, size) {
    const grad = ctx.createLinearGradient(0, 0, size, size);
    grad.addColorStop(0, '#c9a55e');
    grad.addColorStop(0.5, '#d4b070');
    grad.addColorStop(1, '#b8914a');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, size, size);

    ctx.strokeStyle = 'rgba(100,60,10,0.06)';
    ctx.lineWidth = 1;
    for (let i = 0; i < size; i += 4) {
      ctx.beginPath();
      ctx.moveTo(0, i + Math.sin(i * 0.05) * 3);
      ctx.lineTo(size, i + Math.sin(i * 0.05 + 1) * 3);
      ctx.stroke();
    }
  }

  function drawGrid(ctx, size, padding, cell, n) {
    ctx.strokeStyle = '#7a5c2e';
    ctx.lineWidth = 0.8;
    for (let i = 0; i < n; i++) {
      const p = padding + i * cell;
      ctx.beginPath();
      ctx.moveTo(p, padding);
      ctx.lineTo(p, padding + (n - 1) * cell);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(padding, p);
      ctx.lineTo(padding + (n - 1) * cell, p);
      ctx.stroke();
    }
    ctx.strokeStyle = '#6a4c1e';
    ctx.lineWidth = 2;
    ctx.strokeRect(padding, padding, (n - 1) * cell, (n - 1) * cell);
  }

  function drawCoords(ctx, size, padding, cell, n) {
    ctx.fillStyle = 'rgba(80,50,15,0.7)';
    ctx.font = '10px JetBrains Mono, monospace';
    ctx.textAlign = 'center';
    for (let i = 0; i < n; i++) {
      ctx.fillText(letters[i] || String(i + 1), padding + i * cell, padding - 10);
      ctx.fillText(i + 1, padding - 18, padding + i * cell + 4);
    }
  }
  const letters = 'ABCDEFGHJKLMNOPQRSTUVWXYZ';

  function drawStonesGrid(ctx, board, opts, padding, cell) {
    const rows = board.length, cols = board[0].length;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (board[r][c] !== EMPTY) {
          const isWin = opts.winLine && opts.winLine.some(p => p[0] === r && p[1] === c);
          const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
          drawStoneAt(ctx, padding + c * cell, padding + r * cell, cell * 0.44,
            board[r][c], isLast, isWin);
        }
      }
    }
  }

  function hitGrid(mx, my, board, padding, cell) {
    const rows = board.length, cols = board[0].length;
    const col = Math.round((mx - padding) / cell);
    const row = Math.round((my - padding) / cell);
    if (row < 0 || row >= rows || col < 0 || col >= cols) return null;
    return { row, col };
  }

  function ghostGrid(ctx, row, col, side, padding, cell) {
    const x = padding + col * cell, y = padding + row * cell;
    ctx.save();
    ctx.globalAlpha = 0.4;
    ctx.fillStyle = side === BLACK ? '#111' : '#eee';
    ctx.beginPath();
    ctx.arc(x, y, cell * 0.44, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  // ============================================================
  // 五子棋
  // ============================================================
  const GOMOKU = {
    name: '五子棋',
    rows: 15, cols: 15,
    cell: 40, padding: 30,
    stepTime: 30, totalTime: 300,
    footer: '15×15 标准五子棋 · 黑棋先手 · 五连即胜',
    sides: [{ icon: 'black', label: '黑' }, { icon: 'white', label: '白' }],
    icon: '⚫',
    canvasSize() { return this.padding * 2 + this.cell * (this.cols - 1); },

    initialBoard() { return emptyBoard(this.rows, this.cols); },

    draw(ctx, board, opts) {
      const size = this.canvasSize();
      drawWood(ctx, size);
      drawGrid(ctx, size, this.padding, this.cell, this.cols);
      // 星位
      const stars = [[7, 7], [3, 3], [3, 11], [11, 3], [11, 11]];
      ctx.fillStyle = '#6a4c1e';
      stars.forEach(([r, c]) => {
        ctx.beginPath();
        ctx.arc(this.padding + c * this.cell, this.padding + r * this.cell, 4, 0, Math.PI * 2);
        ctx.fill();
      });
      drawCoords(ctx, size, this.padding, this.cell, this.cols);
      drawStonesGrid(ctx, board, opts, this.padding, this.cell);
    },

    hitTest(mx, my, board) {
      return hitGrid(mx, my, board, this.padding, this.cell);
    },

    drawGhost(ctx, row, col, side) {
      ghostGrid(ctx, row, col, side, this.padding, this.cell);
    },

    // 五子棋任意空点皆可下（服务端权威校验胜负）
    isLegal(board, row, col) {
      return board[row] && board[row][col] === EMPTY;
    },

    legalMoves() { return []; },

    applyEffects(board, effects) { return null; }
  };

  // ============================================================
  // 围棋 9 路
  // ============================================================
  const GO9 = {
    name: '围棋 9路',
    rows: 9, cols: 9,
    cell: 50, padding: 34,
    stepTime: 60, totalTime: 600,
    footer: '9×9 围棋 · 黑贴 7.5 目 · 双方连续停一手终局',
    sides: [{ icon: 'black', label: '黑' }, { icon: 'white', label: '白' }],
    icon: '◆',
    canvasSize() { return this.padding * 2 + this.cell * (this.cols - 1); },

    initialBoard() { return emptyBoard(this.rows, this.cols); },

    draw(ctx, board, opts) {
      const size = this.canvasSize();
      drawWood(ctx, size);
      drawGrid(ctx, size, this.padding, this.cell, this.cols);
      // 星位（9路：三三、天元）
      const stars = [[2, 2], [2, 6], [4, 4], [6, 2], [6, 6]];
      ctx.fillStyle = '#6a4c1e';
      stars.forEach(([r, c]) => {
        ctx.beginPath();
        ctx.arc(this.padding + c * this.cell, this.padding + r * this.cell, 3.5, 0, Math.PI * 2);
        ctx.fill();
      });
      drawCoords(ctx, size, this.padding, this.cell, this.cols);
      drawStonesGrid(ctx, board, opts, this.padding, this.cell);
    },

    hitTest(mx, my, board) {
      return hitGrid(mx, my, board, this.padding, this.cell);
    },

    drawGhost(ctx, row, col, side) {
      ghostGrid(ctx, row, col, side, this.padding, this.cell);
    },

    // 本地粗校验：空点即可（自杀/劫由服务端判定）
    isLegal(board, row, col) {
      return board[row] && board[row][col] === EMPTY;
    },

    legalMoves() { return []; },

    applyEffects(board, effects) { return null; }
  };

  // ============================================================
  // 黑白棋
  // ============================================================
  const REVERSI = {
    name: '黑白棋',
    rows: 8, cols: 8,
    cell: 58, padding: 10,
    stepTime: 60, totalTime: 300,
    footer: '8×8 黑白棋 · 夹住翻转 · 终局子多者胜',
    sides: [{ icon: 'black', label: '黑' }, { icon: 'white', label: '白' }],
    icon: '◐',
    canvasSize() { return this.padding * 2 + this.cell * this.cols; },

    // 标准开局：中央四子（(3,3)/(4,4) 白，(3,4)/(4,3) 黑，与服务端一致）
    initialBoard() {
      const b = emptyBoard(this.rows, this.cols);
      b[3][3] = WHITE;
      b[3][4] = BLACK;
      b[4][3] = BLACK;
      b[4][4] = WHITE;
      return b;
    },

    draw(ctx, board, opts) {
      const size = this.canvasSize();
      const pad = this.padding, cell = this.cell;

      // 深绿呢面背景
      const grad = ctx.createLinearGradient(0, 0, size, size);
      grad.addColorStop(0, '#1e6b45');
      grad.addColorStop(0.5, '#25835a');
      grad.addColorStop(1, '#1a5c3c');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, size, size);

      // 格线
      ctx.strokeStyle = 'rgba(0,0,0,0.35)';
      ctx.lineWidth = 1;
      for (let i = 0; i <= this.cols; i++) {
        const p = pad + i * cell;
        ctx.beginPath(); ctx.moveTo(p, pad); ctx.lineTo(p, pad + this.rows * cell); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(pad, p); ctx.lineTo(pad + this.cols * cell, p); ctx.stroke();
      }
      // 外框
      ctx.strokeStyle = '#0f3d27';
      ctx.lineWidth = 3;
      ctx.strokeRect(pad, pad, this.cols * cell, this.rows * cell);

      // 四个定位点
      ctx.fillStyle = 'rgba(0,0,0,0.3)';
      [[2, 2], [2, 6], [6, 2], [6, 6]].forEach(([r, c]) => {
        ctx.beginPath();
        ctx.arc(pad + c * cell + cell / 2, pad + r * cell + cell / 2, 3, 0, Math.PI * 2);
        ctx.fill();
      });

      // 棋子
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          if (board[r][c] !== EMPTY) {
            const x = pad + c * cell + cell / 2;
            const y = pad + r * cell + cell / 2;
            const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
            this.drawDisc(ctx, x, y, cell * 0.42, board[r][c], isLast);
          }
        }
      }

      // 合法手提示（轮到自己时）
      if (opts.hint && opts.myStone && opts.myStone === opts.currentTurn) {
        const moves = this.legalMoves(board, opts.myStone);
        ctx.fillStyle = 'rgba(255,215,100,0.55)';
        for (const m of moves) {
          const x = pad + m[1] * cell + cell / 2;
          const y = pad + m[0] * cell + cell / 2;
          ctx.beginPath();
          ctx.arc(x, y, cell * 0.12, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    },

    drawDisc(ctx, x, y, r, side, isLast) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.45)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetX = 1;
      ctx.shadowOffsetY = 2;
      const grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
      if (side === BLACK) {
        grad.addColorStop(0, '#4a4a4a');
        grad.addColorStop(0.5, '#1c1c1c');
        grad.addColorStop(1, '#000');
      } else {
        grad.addColorStop(0, '#ffffff');
        grad.addColorStop(0.6, '#eeeae2');
        grad.addColorStop(1, '#c9c4ba');
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      if (isLast) {
        ctx.strokeStyle = 'rgba(255,90,90,0.95)';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r + 2, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my, board) {
      const pad = this.padding, cell = this.cell;
      const col = Math.floor((mx - pad) / cell);
      const row = Math.floor((my - pad) / cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },

    drawGhost(ctx, row, col, side) {
      const pad = this.padding, cell = this.cell;
      const x = pad + col * cell + cell / 2;
      const y = pad + row * cell + cell / 2;
      ctx.save();
      ctx.globalAlpha = 0.45;
      this.drawDisc(ctx, x, y, cell * 0.42, side, false);
      ctx.restore();
    },

    isLegal(board, row, col, side) {
      if (board[row] && board[row][col] !== EMPTY) return false;
      return this.flipsFor(board, row, col, side).length > 0;
    },

    legalMoves(board, side) {
      const moves = [];
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          if (board[r][c] === EMPTY && this.flipsFor(board, r, c, side).length > 0) {
            moves.push([r, c]);
          }
        }
      }
      return moves;
    },

    // 本地镜像：某点落子会翻转的棋子（与服务端同规则）
    flipsFor(board, row, col, side) {
      if (board[row][col] !== EMPTY) return [];
      const opponent = side === BLACK ? WHITE : BLACK;
      const dirs = [[-1,-1],[-1,0],[-1,1],[0,-1],[0,1],[1,-1],[1,0],[1,1]];
      const flips = [];
      for (const [dr, dc] of dirs) {
        const line = [];
        let r = row + dr, c = col + dc;
        while (r >= 0 && r < this.rows && c >= 0 && c < this.cols && board[r][c] === opponent) {
          line.push([r, c]);
          r += dr; c += dc;
        }
        if (line.length && r >= 0 && r < this.rows && c >= 0 && c < this.cols && board[r][c] === side) {
          flips.push(...line);
        }
      }
      return flips;
    },

    // 应用服务端效果：[[r,c,newSide],...]；自动停一手标记 [EFFECT_AUTO_PASS_ROW, side, 0]
    applyEffects(board, effects) {
      if (!effects || !effects.length) return null;
      let autoPassSide = null;
      for (const e of effects) {
        if (e[0] === -1) {
          autoPassSide = e[1]; // 该方被自动停一手
        } else {
          board[e[0]][e[1]] = e[2];
        }
      }
      return autoPassSide;
    }
  };

  // ============================================================
  // 中国象棋
  // ============================================================
  // 棋子编码与服务端一致：side*10+type（11..17 红 / 21..27 黑）
  const XIANGQI_GLYPHS = {
    11: '帥', 12: '仕', 13: '相', 14: '馬', 15: '車', 16: '炮', 17: '兵',
    21: '將', 22: '士', 23: '象', 24: '馬', 25: '車', 26: '砲', 27: '卒'
  };

  const XIANGQI = {
    name: '中国象棋',
    rows: 10, cols: 9,
    cell: 52, padding: 28,
    stepTime: 60, totalTime: 600,
    footer: '9×10 中国象棋 · 红先行 · 将死或困毙获胜',
    icon: '♞',
    usePieceValues: true,
    sides: [{ icon: 'red', label: '红' }, { icon: 'black', label: '黑' }],

    canvasWidth() { return this.padding * 2 + this.cell * (this.cols - 1); },
    canvasHeight() { return this.padding * 2 + this.cell * (this.rows - 1); },

    initialBoard() {
      // 与服务端初始局面一致
      const b = emptyBoard(this.rows, this.cols);
      const top = [25, 24, 23, 22, 21, 22, 23, 24, 25];
      const bottom = [15, 14, 13, 12, 11, 12, 13, 14, 15];
      for (let c = 0; c < 9; c++) {
        b[0][c] = top[c];
        b[9][c] = bottom[c];
      }
      b[2][1] = 26; b[2][7] = 26;
      b[7][1] = 16; b[7][7] = 16;
      for (const c of [0, 2, 4, 6, 8]) {
        b[3][c] = 27;
        b[6][c] = 17;
      }
      return b;
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();

      // 长方形木纹背景（纵向渐变 + 纹理）
      const bgGrad = ctx.createLinearGradient(0, 0, 0, H);
      bgGrad.addColorStop(0, '#c9a55e');
      bgGrad.addColorStop(0.5, '#d4b070');
      bgGrad.addColorStop(1, '#b8914a');
      ctx.fillStyle = bgGrad;
      ctx.fillRect(0, 0, W, H);
      ctx.strokeStyle = 'rgba(100,60,10,0.06)';
      ctx.lineWidth = 1;
      for (let i = 0; i < H; i += 4) {
        ctx.beginPath();
        ctx.moveTo(0, i + Math.sin(i * 0.05) * 3);
        ctx.lineTo(W, i + Math.sin(i * 0.05 + 1) * 3);
        ctx.stroke();
      }

      // 横线（10 条）
      ctx.strokeStyle = '#7a5c2e';
      ctx.lineWidth = 1;
      for (let r = 0; r < this.rows; r++) {
        ctx.beginPath();
        ctx.moveTo(pad, pad + r * cell);
        ctx.lineTo(W - pad, pad + r * cell);
        ctx.stroke();
      }
      // 竖线：两边贯通，中间被楚河断开
      for (let c = 0; c < this.cols; c++) {
        if (c === 0 || c === this.cols - 1) {
          ctx.beginPath();
          ctx.moveTo(pad + c * cell, pad);
          ctx.lineTo(pad + c * cell, H - pad);
          ctx.stroke();
        } else {
          ctx.beginPath();
          ctx.moveTo(pad + c * cell, pad);
          ctx.lineTo(pad + c * cell, pad + 4 * cell);
          ctx.stroke();
          ctx.beginPath();
          ctx.moveTo(pad + c * cell, pad + 5 * cell);
          ctx.lineTo(pad + c * cell, H - pad);
          ctx.stroke();
        }
      }
      // 外框加粗
      ctx.strokeStyle = '#6a4c1e';
      ctx.lineWidth = 2;
      ctx.strokeRect(pad, pad, 8 * cell, 9 * cell);

      // 九宫斜线
      ctx.lineWidth = 1;
      const palace = (r0) => {
        ctx.beginPath();
        ctx.moveTo(pad + 3 * cell, pad + r0 * cell);
        ctx.lineTo(pad + 5 * cell, pad + (r0 + 2) * cell);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(pad + 5 * cell, pad + r0 * cell);
        ctx.lineTo(pad + 3 * cell, pad + (r0 + 2) * cell);
        ctx.stroke();
      };
      palace(0);
      palace(7);

      // 楚河汉界
      ctx.fillStyle = 'rgba(90,55,20,0.75)';
      ctx.font = '26px Noto Serif SC, serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('楚 河', pad + 2 * cell, pad + 4.5 * cell);
      ctx.fillText('汉 界', pad + 6 * cell, pad + 4.5 * cell);
      ctx.textBaseline = 'alphabetic';

      // 棋子
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const v = board[r][c];
          if (v === 0) continue;
          const x = pad + c * cell, y = pad + r * cell;
          const side = Math.floor(v / 10);
          const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
          const isWin = opts.winLine && opts.winLine.some(p => p[0] === r && p[1] === c);
          this.drawPiece(ctx, x, y, cell * 0.44, side, XIANGQI_GLYPHS[v] || '?', isLast, isWin);
        }
      }

      // 选中高亮
      if (opts.selected) {
        const x = pad + opts.selected.col * cell;
        const y = pad + opts.selected.row * cell;
        ctx.strokeStyle = 'rgba(255,215,100,0.95)';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(x, y, cell * 0.48, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    drawPiece(ctx, x, y, r, side, glyph, isLast, isWin) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.45)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetX = 1;
      ctx.shadowOffsetY = 2;
      // 象牙底
      const grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
      grad.addColorStop(0, '#fff8e8');
      grad.addColorStop(0.7, '#f0e2c0');
      grad.addColorStop(1, '#cdb98e');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      // 圈线
      ctx.strokeStyle = side === 1 ? '#c0392b' : '#2c3e50';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(x, y, r * 0.82, 0, Math.PI * 2);
      ctx.stroke();
      // 字
      ctx.fillStyle = side === 1 ? '#c0392b' : '#1f2d3d';
      ctx.font = `bold ${Math.round(r * 1.1)}px Noto Serif SC, serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(glyph, x, y + 1);
      ctx.textBaseline = 'alphabetic';
      if (isLast) {
        ctx.strokeStyle = 'rgba(255,90,90,0.9)';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r + 3, 0, Math.PI * 2);
        ctx.stroke();
      }
      if (isWin) {
        ctx.strokeStyle = 'rgba(255,60,60,0.9)';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(x, y, r + 5, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my, board) {
      const pad = this.padding, cell = this.cell;
      const col = Math.round((mx - pad) / cell);
      const row = Math.round((my - pad) / cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      // 距交叉点太远不响应
      const dx = mx - (pad + col * cell), dy = my - (pad + row * cell);
      if (dx * dx + dy * dy > (cell * 0.48) * (cell * 0.48)) return null;
      return { row, col };
    },

    drawGhost() { /* 象棋用选中高亮代替预览 */ },

    isLegal(board, row, col, side) {
      // 点击处可为空点或对方棋子（选子/落点）
      const v = board[row] ? board[row][col] : 0;
      return v === 0 || Math.floor(v / 10) !== side;
    },

    legalMoves() { return []; },

    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) {
        board[e[0]][e[1]] = e[2];
      }
      return null;
    }
  };

  // ============================================================
  // 四子棋
  // ============================================================
  const CONNECT4 = {
    name: '四子棋',
    rows: 6, cols: 7,
    cell: 60, padding: 10,
    stepTime: 30, totalTime: 300,
    footer: '7×6 四子棋 · 横竖斜四子连线获胜 · 落子下坠',
    icon: '◉',
    sides: [{ icon: 'red', label: '红' }, { icon: 'yellow', label: '黄' }],

    canvasWidth() { return this.padding * 2 + this.cell * this.cols; },
    canvasHeight() { return this.padding * 2 + this.cell * this.rows; },

    initialBoard() { return emptyBoard(this.rows, this.cols); },

    dropRow(board, col) {
      if (col < 0 || col >= this.cols) return -1;
      for (let r = this.rows - 1; r >= 0; r--) {
        if (board[r][col] === EMPTY) return r;
      }
      return -1;
    },

    // 把「点击的格子」换算成「受重力决定的真实落点」。
    // 四子棋点哪一格都表示选这一列，棋子只会落在该列最底部空位；
    // 若不做这层换算，玩家必须精确点中最底空格才能落子，点上方空格会被判非法。
    normalizeMove(board, row, col) {
      const r = this.dropRow(board, col);
      return r < 0 ? null : { row: r, col };
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();

      // 蓝色棋盘背景
      const grad = ctx.createLinearGradient(0, 0, 0, H);
      grad.addColorStop(0, '#2456a8');
      grad.addColorStop(1, '#173a75');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);
      ctx.strokeStyle = '#0e254d';
      ctx.lineWidth = 3;
      ctx.strokeRect(1.5, 1.5, W - 3, H - 3);

      // 网孔 + 棋子
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const x = pad + c * cell + cell / 2;
          const y = pad + r * cell + cell / 2;
          const holeR = cell * 0.42;
          if (board[r][c] === EMPTY) {
            ctx.fillStyle = '#0d1e3a';
            ctx.beginPath();
            ctx.arc(x, y, holeR, 0, Math.PI * 2);
            ctx.fill();
          } else {
            ctx.fillStyle = '#0d1e3a';
            ctx.beginPath();
            ctx.arc(x, y, holeR, 0, Math.PI * 2);
            ctx.fill();
            const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
            const isWin = opts.winLine && opts.winLine.some(p => p[0] === r && p[1] === c);
            this.drawDisc(ctx, x, y, holeR * 0.92, board[r][c], isLast, isWin);
          }
        }
      }

      // 合法落点提示（轮到自己时）
      if (opts.hint && opts.myStone && opts.myStone === opts.currentTurn) {
        for (let c = 0; c < this.cols; c++) {
          const r = this.dropRow(board, c);
          if (r < 0) continue;
          const x = pad + c * cell + cell / 2;
          const y = pad + r * cell + cell / 2;
          ctx.fillStyle = 'rgba(255,215,100,0.6)';
          ctx.beginPath();
          ctx.arc(x, y, cell * 0.12, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    },

    drawDisc(ctx, x, y, r, side, isLast, isWin) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.4)';
      ctx.shadowBlur = 5;
      ctx.shadowOffsetX = 1;
      ctx.shadowOffsetY = 2;
      const grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, 0, x, y, r);
      if (side === 1) {
        grad.addColorStop(0, '#ff8a80');
        grad.addColorStop(0.5, '#e53935');
        grad.addColorStop(1, '#a91e1a');
      } else {
        grad.addColorStop(0, '#fff176');
        grad.addColorStop(0.5, '#fdd835');
        grad.addColorStop(1, '#c6a700');
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      if (isLast) {
        ctx.strokeStyle = 'rgba(255,255,255,0.9)';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r - 1, 0, Math.PI * 2);
        ctx.stroke();
      }
      if (isWin) {
        ctx.strokeStyle = 'rgba(120,255,140,0.95)';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(x, y, r + 3, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my, board) {
      const pad = this.padding, cell = this.cell;
      const col = Math.floor((mx - pad) / cell);
      const row = Math.floor((my - pad) / cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },

    drawGhost(ctx, row, col, side) {
      const pad = this.padding, cell = this.cell;
      const x = pad + col * cell + cell / 2;
      const y = pad + row * cell + cell / 2;
      ctx.save();
      ctx.globalAlpha = 0.5;
      this.drawDisc(ctx, x, y, cell * 0.38, side, false, false);
      ctx.restore();
    },

    isLegal(board, row, col, side) {
      const drop = this.dropRow(board, col);
      return drop >= 0 && board[row] && board[row][col] === EMPTY;
    },

    legalMoves(board) {
      const moves = [];
      for (let c = 0; c < this.cols; c++) {
        const r = this.dropRow(board, c);
        if (r >= 0) moves.push([r, c]);
      }
      return moves;
    },

    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) {
        board[e[0]][e[1]] = e[2];
      }
      return null;
    }
  };

  // ============================================================
  // 回合制 RPG 对战
  // ============================================================
  // 第一个非棋盘玩法：没有 canvas 盘面，渲染走 HTML 面板（app.js 的 renderRpgPanel）。
  // 模块里只保留大厅/计时/文案需要的元信息，画布相关方法给空实现 ——
  // app.js 见到 html: true 就会跳过所有画布调用，所以这些空实现不会被真正用到，
  // 保留它们只是为了不破坏「模块统一形状」这个约定。
  const RPG = {
    name: '回合制对战',
    html: true,
    panel: 'rpg-panel',
    render: 'renderRpgPanel',
    stepTime: 30, totalTime: 300,
    footer: '回合制对战 · 攻击/技能/防御/药水 · 血量归零判负',
    icon: '⚔',
    sides: [{ icon: 'red', label: '红' }, { icon: 'blue', label: '蓝' }],
    // 行动序号 → 名称，复盘时把 {side, action, value} 还原成可读的战斗日志
    actionLabels: ['普通攻击', '强力一击', '火球术', '治疗术', '防御', '喝药水'],

    initialBoard() { return null; },
    draw() {},
    hitTest() { return null; },
    drawGhost() {},
    isLegal() { return false; },
    applyEffects() { return null; },
    legalMoves() { return []; }
  };

  // ============================================================
  // 卡牌对战
  // ============================================================
  // 第二个非棋盘玩法，同样走 HTML 面板（app.js 的 renderCardPanel）。
  // 与 RPG 的差别全在服务端：卡牌有隐藏信息（对手手牌不可见），
  // 前端拿到的 view 已经按视角裁好，这里只负责画，不做任何权限判断。
  const CARD = {
    name: '卡牌对战',
    html: true,
    panel: 'card-panel',
    render: 'renderCardPanel',
    stepTime: 45, totalTime: 600,
    footer: '卡牌对战 · 手牌/法力/随从 · 英雄生命归零判负',
    icon: '🂠',
    sides: [{ icon: 'red', label: '红' }, { icon: 'blue', label: '蓝' }],
    // 手牌按下标发送，复盘时把 {side, card, value} 还原成可读记录
    actionLabels: ['步兵', '弓箭手', '骑士', '守卫', '幼龙',
                   '火球术', '治疗术', '横扫', '鼓舞'],

    initialBoard() { return null; },
    draw() {},
    hitTest() { return null; },
    drawGhost() {},
    isLegal() { return false; },
    applyEffects() { return null; },
    legalMoves() { return []; }
  };

  // ============================================================
  // 取石子博弈
  // ============================================================
  // 行动语义是「从第几堆拿几个」，比技能下标更接近坐标，
  // 所以后端用 row=堆号 / col=数量 承载，前端直接发 MOVE。
  const NIM = {
    name: '取石子',
    html: true,
    panel: 'nim-panel',
    render: 'renderNimPanel',
    stepTime: 30, totalTime: 300,
    footer: '取石子博弈 · 三堆石子 · 拿走最后一颗者胜',
    icon: '●',
    sides: [{ icon: 'red', label: '红' }, { icon: 'blue', label: '蓝' }],
    // 回放文字：{side, pile, removed}
    actionLabels: ['第 1 堆', '第 2 堆', '第 3 堆'],

    initialBoard() { return null; },
    draw() {},
    hitTest() { return null; },
    drawGhost() {},
    isLegal() { return false; },
    applyEffects() { return null; },
    legalMoves() { return []; }
  };

  // ============================================================
  // 答题对战
  // ============================================================
  // 双方答同一题；提交前看不到对手选择，也看不到正确答案 ——
  // 服务端 getViewState(viewerSide) 已按视角裁好。
  const QUIZ = {
    name: '答题对战',
    html: true,
    panel: 'quiz-panel',
    render: 'renderQuizPanel',
    stepTime: 25, totalTime: 400,
    footer: '答题对战 · 8 题 · 答对 +10 / 答错 -5',
    icon: '?',
    sides: [{ icon: 'red', label: '红' }, { icon: 'blue', label: '蓝' }],
    actionLabels: ['A 选项', 'B 选项', 'C 选项', 'D 选项'],

    initialBoard() { return null; },
    draw() {},
    hitTest() { return null; },
    drawGhost() {},
    isLegal() { return false; },
    applyEffects() { return null; },
    legalMoves() { return []; }
  };

  const DICE_RACE = {
    name: '骰子竞速', html: true, panel: 'dice-race-panel', render: 'renderDiceRacePanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 25, totalTime: 240, icon: '🎲',
    footer: '2–8 人 · 稳健/冒险/追击 · 六轮累计得分',
    actionLabels: ['稳健', '冒险', '追击'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const BLACKJACK = {
    name: '21 点', html: true, panel: 'blackjack-panel', render: 'renderBlackjackPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '🂡',
    footer: '2–8 人 · 依次要牌/停牌 · 庄家 17 点停牌 · 爆牌即负',
    actionLabels: ['要牌', '停牌'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const GEM_AUCTION = {
    name: '宝石拍卖', html: true, panel: 'gem-auction-panel', render: 'renderGemAuctionPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '💎',
    footer: '2–8 人 · 暗投金币 · 五轮争夺 3/5/7/9/12 分宝石',
    actionLabels: ['出价 0', '出价 1', '出价 2', '出价 3', '出价 4', '出价 5', '出价 6', '出价 7', '出价 8', '出价 9', '出价 10'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const HIGH_CARD = {
    name: '比大小', html: true, panel: 'high-card-panel', render: 'renderHighCardPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '♠',
    footer: '2–8 人 · 五轮暗选 · 一次王牌翻倍 · 决胜轮双倍计分',
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const UNO = {
    name: 'UNO 对战', html: true, panel: 'uno-panel', render: 'renderUnoPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4, stepTime: 30, totalTime: 300, icon: '🃏',
    footer: '2–8 人 · 按颜色或数字出牌 · 无牌可出时摸牌',
    actionLabels: ['摸一张'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; }, drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  window.GameModules = { HIGH_CARD, BLACKJACK, GOMOKU, REVERSI, GO9, XIANGQI, CONNECT4, RPG, CARD, NIM, QUIZ, DICE_RACE, GEM_AUCTION, UNO };
})();

