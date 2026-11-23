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
    rules: {
      goal: '先在横、竖或斜线上连成五子。',
      steps: [
        '双方轮流在棋盘交叉点落子，黑棋先手。',
        '已有棋子的交叉点不能重复落子。',
        '任意方向连成五子即获胜；棋盘下满仍无人连成五子则平局。'
      ],
      tips: ['先手要抢中心与斜线，后手优先堵对手的活三和冲四。', '落子前先看对手是否有两端都能成五的活四。']
    },
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
    rules: {
      goal: '终局时占据更多地盘；白棋另有 7.5 目贴目。',
      steps: [
        '双方轮流在交叉点落子，黑棋先手。',
        '被对方棋子完全包围、没有气的棋块会被提走。',
        '不能自杀，也不能立即回提形成简单劫；无棋可下或想结束回合时点「停一手」。',
        '双方连续停一手后按数子法结算：活子加各自独占的空点，白棋再加 7.5 目。'
      ],
      tips: ['9 路棋盘空间小，先确保棋块有两只真眼。', '提子前先算清对方能否反提，避免劫争吃亏。']
    },
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
    rules: {
      goal: '终局时棋盘上自己的棋子更多。',
      steps: [
        '黑棋先手。每步必须落在能夹住对方棋子的空格上，横、竖、斜均可。',
        '被夹住的对方棋子会全部翻成己方颜色。',
        '若当前没有合法落点，系统会自动停一手；双方都无合法落点或棋盘下满即终局。'
      ],
      tips: ['不要只看眼前翻转数量，角落和边线更稳。', '中后期减少可被对方利用的「内线」落点。']
    },
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
    rules: {
      goal: '将死或困毙对方的将/帅。',
      steps: [
        '红方先手，双方轮流走一枚己方棋子，不能吃自己的棋子。',
        '完整执行马蹩腿、象塞眼、炮隔子打、兵过河、九宫限制等规则。',
        '将帅不能照面；被将军时必须解除将军。',
        '对方将/帅被将死或无子可走（困毙）即获胜。'
      ],
      tips: ['先出车马控制要道，不要过早暴露将帅。', '将军前先确认自己的棋子不会反被吃掉。']
    },
    icon: '♞',
    moveSelect: true,
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
  // 西洋跳棋
  // ============================================================
  const CHECKERS = {
    name: '西洋跳棋',
    rows: 8, cols: 8,
    cell: 62, padding: 18,
    stepTime: 45, totalTime: 480,
    footer: '8×8 西洋跳棋 · 强制吃子与连吃 · 抵达底线升王',
    rules: {
      goal: '吃光对方棋子，或让对方无子可走。',
      steps: [
        '红方先手，棋子只能沿深色格斜向前进一格；兵不能后退，王可以任意斜向移动一格。',
        '若相邻格有对方棋子、其后方空格为空，可以跳过并吃掉它。',
        '只要存在吃子就必须吃；连吃时同一枚棋子继续跳，直到不能继续吃为止。',
        '兵抵达对方底线即升为王。一方无子或无合法走法即判负。'
      ],
      tips: ['优先保住通往底线的通路，升王后战力大幅提升。', '不要把棋子送到对手的连吃路线上。']
    },
    icon: '⛀',
    moveSelect: true,
    usePieceValues: true,
    sides: [{ icon: 'red', label: '红' }, { icon: 'black', label: '黑' }],

    canvasWidth() { return this.padding * 2 + this.cell * this.cols; },
    canvasHeight() { return this.padding * 2 + this.cell * this.rows; },

    initialBoard() {
      const b = emptyBoard(this.rows, this.cols);
      for (let r = 0; r < 3; r++) for (let c = 0; c < this.cols; c++) {
        if ((r + c) % 2 === 1) b[r][c] = 21;
      }
      for (let r = 5; r < 8; r++) for (let c = 0; c < this.cols; c++) {
        if ((r + c) % 2 === 1) b[r][c] = 11;
      }
      return b;
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();
      ctx.fillStyle = '#3b2a1a';
      ctx.fillRect(0, 0, W, H);
      const view = opts.view;
      const legal = (view && Array.isArray(view.legalMoves)) ? view.legalMoves : [];
      const chain = view && Array.isArray(view.chainFrom) ? view.chainFrom : null;
      const mustCapture = legal.length > 0 && legal.every(m => m.capture);
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const x = pad + c * cell, y = pad + r * cell;
          ctx.fillStyle = (r + c) % 2 === 1 ? '#8b5a2b' : '#e8d6b3';
          ctx.fillRect(x, y, cell, cell);
          const v = board[r][c];
          if (v !== 0) {
            const side = Math.floor(v / 10);
            const king = v % 10 === 2;
            const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
            this.drawPiece(ctx, x + cell / 2, y + cell / 2, cell * 0.36, side, king, isLast);
          }
        }
      }
      // 合法目标提示：强制吃子时只亮出吃子落点；连吃时将可继续跳的落点标出来。
      if (opts.hint && legal.length) {
        const targets = new Map();
        for (const m of legal) {
          const key = m.toRow + ',' + m.toCol;
          targets.set(key, m.capture ? 'capture' : 'move');
        }
        for (const [key, kind] of targets) {
          const [tr, tc] = key.split(',').map(Number);
          const cx = pad + tc * cell + cell / 2;
          const cy = pad + tr * cell + cell / 2;
          ctx.beginPath();
          ctx.arc(cx, cy, kind === 'capture' ? cell * 0.22 : cell * 0.14, 0, Math.PI * 2);
          ctx.fillStyle = kind === 'capture' ? 'rgba(255,92,72,0.55)' : 'rgba(80,220,130,0.38)';
          ctx.fill();
          if (kind === 'capture') {
            ctx.strokeStyle = 'rgba(255,190,120,0.9)';
            ctx.lineWidth = 2;
            ctx.stroke();
          }
        }
      }
      if (opts.selected) {
        const x = pad + opts.selected.col * cell, y = pad + opts.selected.row * cell;
        ctx.strokeStyle = 'rgba(255,215,100,0.95)';
        ctx.lineWidth = 4;
        ctx.strokeRect(x + 3, y + 3, cell - 6, cell - 6);
      }
      if (opts.hint && chain) {
        const x = pad + chain[1] * cell, y = pad + chain[0] * cell;
        ctx.strokeStyle = 'rgba(120,220,255,0.95)';
        ctx.lineWidth = 4;
        ctx.setLineDash([7, 4]);
        ctx.strokeRect(x + 4, y + 4, cell - 8, cell - 8);
        ctx.setLineDash([]);
      }
      // 强制吃子提示放在棋盘底部，避免玩家误以为普通移动被吞掉
      if (opts.hint && mustCapture) {
        ctx.fillStyle = 'rgba(255,230,180,0.92)';
        ctx.font = 'bold 15px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'bottom';
        ctx.fillText(chain ? '继续连吃：点击高亮落点' : '必须吃子：点击红圈落点', W / 2, H - 4);
        ctx.textBaseline = 'alphabetic';
        ctx.textAlign = 'start';
      }
    },

    drawPiece(ctx, x, y, r, side, king, isLast) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.45)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetY = 2;
      const grad = ctx.createRadialGradient(x - r * 0.35, y - r * 0.35, 0, x, y, r);
      if (side === 1) {
        grad.addColorStop(0, '#ff8b80');
        grad.addColorStop(0.55, '#d63a2f');
        grad.addColorStop(1, '#8e1d16');
      } else {
        grad.addColorStop(0, '#6b6b6b');
        grad.addColorStop(0.55, '#262626');
        grad.addColorStop(1, '#050505');
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      if (king) {
        ctx.fillStyle = '#f6d365';
        ctx.font = `bold ${Math.round(r * 0.95)}px serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('♛', x, y + 1);
        ctx.textBaseline = 'alphabetic';
      }
      if (isLast) {
        ctx.strokeStyle = 'rgba(255,255,255,0.95)';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(x, y, r - 2, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my) {
      const pad = this.padding, cell = this.cell;
      const col = Math.floor((mx - pad) / cell);
      const row = Math.floor((my - pad) / cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },

    drawGhost() {},

    isLegal(board, row, col, side) {
      const v = board[row] ? board[row][col] : 0;
      return v === 0 || Math.floor(v / 10) !== side;
    },

    legalMoves() { return []; },

    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) board[e[0]][e[1]] = e[2];
      return null;
    }
  };

  // ============================================================
  // 斗兽棋（9×7，八兽相克 + 河流/陷阱/兽穴）
  // ============================================================
  const JUNGLE_GLYPHS = { 1: '鼠', 2: '猫', 3: '狗', 4: '狼', 5: '豹', 6: '虎', 7: '狮', 8: '象' };
  const JUNGLE_TRAPS = [[0, 2], [0, 4], [1, 3], [8, 2], [8, 4], [7, 3]];
  const JUNGLE_DENS = [[0, 3], [8, 3]];
  const JUNGLE_IN_WATER = (r, c) => r >= 3 && r <= 5 && ((c >= 1 && c <= 2) || (c >= 4 && c <= 5));
  const JUNGLE_TRAP = (r, c) => JUNGLE_TRAPS.some(([rr, cc]) => rr === r && cc === c);

  const JUNGLE = {
    name: '斗兽棋',
    rows: 9, cols: 7,
    cell: 66, padding: 22,
    stepTime: 45, totalTime: 600,
    footer: '9×7 斗兽棋 · 红方先行 · 八兽相克 · 入对方兽穴获胜',
    rules: {
      goal: '进入对方的兽穴，或吃光对方、让对手无棋可走。',
      steps: [
        '红方先手，每回合沿上下左右移动一枚己方棋子一格；不能进入自己的兽穴。',
        '战力从高到低：象 > 狮 > 虎 > 豹 > 狼 > 狗 > 猫 > 鼠；同战力可互吃，鼠可吃象，象不能吃鼠。',
        '只有鼠能进入河流；岸上棋子不能吃水中的鼠，水中的鼠也不能吃岸上棋子。狮、虎可以纵跳整段河流，但河里有任意鼠时不能跳。',
        '进入对方陷阱的棋子战力视为 0，可被任意敌棋吃掉；进入对方兽穴立即获胜。',
        '一方被吃光或无合法走法即负；连续 120 个半回合无吃子判和。'
      ],
      tips: ['优先守住自己的兽穴入口，并用鼠挡住对手的狮虎跳河路线。', '高战力棋子进入对方陷阱会变弱，先派低战力棋子探路。']
    },
    icon: '🐯',
    moveSelect: true,
    usePieceValues: true,
    sides: [{ icon: 'red', label: '红' }, { icon: 'black', label: '黑' }],

    canvasWidth() { return this.padding * 2 + this.cell * this.cols; },
    canvasHeight() { return this.padding * 2 + this.cell * this.rows; },

    initialBoard() {
      const b = emptyBoard(this.rows, this.cols);
      b[0][0] = 27; b[0][6] = 26;
      b[1][1] = 23; b[1][5] = 22;
      b[2][0] = 21; b[2][2] = 25; b[2][4] = 24; b[2][6] = 28;
      b[8][0] = 16; b[8][6] = 17;
      b[7][1] = 12; b[7][5] = 13;
      b[6][0] = 18; b[6][2] = 14; b[6][4] = 15; b[6][6] = 11;
      return b;
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();
      const view = opts.view || {};
      const legal = Array.isArray(view.legalMoves) ? view.legalMoves : [];

      const forest = ctx.createLinearGradient(0, 0, W, H);
      forest.addColorStop(0, '#24402f');
      forest.addColorStop(0.5, '#31543b');
      forest.addColorStop(1, '#193126');
      ctx.fillStyle = forest;
      ctx.fillRect(0, 0, W, H);
      for (let i = 0; i < 24; i++) {
        ctx.fillStyle = i % 3 === 0 ? 'rgba(190,220,150,.03)' : 'rgba(0,0,0,.025)';
        ctx.beginPath();
        ctx.arc((i * 97) % W, (i * 53 + 17) % H, 4 + (i % 5), 0, Math.PI * 2);
        ctx.fill();
      }

      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const x = pad + c * cell, y = pad + r * cell;
          const water = JUNGLE_IN_WATER(r, c);
          ctx.fillStyle = water ? '#2d6d86' : ((r + c) % 2 ? '#dfc88c' : '#ead9a9');
          ctx.fillRect(x, y, cell, cell);
          if (water) {
            ctx.strokeStyle = 'rgba(205,244,255,.18)';
            ctx.lineWidth = 1;
            for (let k = 0; k < 3; k++) {
              ctx.beginPath();
              ctx.moveTo(x + 8, y + 18 + k * 15);
              ctx.bezierCurveTo(x + 22, y + 13 + k * 15, x + 37, y + 23 + k * 15, x + 54, y + 17 + k * 15);
              ctx.stroke();
            }
          }
          if (JUNGLE_TRAP(r, c)) {
            ctx.fillStyle = 'rgba(198,93,45,.14)';
            ctx.fillRect(x, y, cell, cell);
            ctx.strokeStyle = 'rgba(115,48,24,.7)';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(x + cell / 2, y + cell / 2, cell * .27, 0, Math.PI * 2);
            ctx.stroke();
            ctx.fillStyle = 'rgba(105,48,28,.62)';
            ctx.font = 'bold ' + Math.round(cell * .16) + 'px sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('陷阱', x + cell / 2, y + cell * .72);
          }
          const denIndex = JUNGLE_DENS.findIndex(([rr, cc]) => rr === r && cc === c);
          if (denIndex >= 0) {
            ctx.fillStyle = denIndex === 0 ? 'rgba(43,52,82,.65)' : 'rgba(137,54,45,.52)';
            ctx.fillRect(x, y, cell, cell);
            ctx.strokeStyle = denIndex === 0 ? '#8194d8' : '#f5b08a';
            ctx.lineWidth = 3;
            ctx.strokeRect(x + 3, y + 3, cell - 6, cell - 6);
            ctx.fillStyle = 'rgba(255,243,210,.86)';
            ctx.font = 'bold ' + Math.round(cell * .18) + 'px sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('兽穴', x + cell / 2, y + cell * .78);
          }
          ctx.strokeStyle = 'rgba(46,52,30,.35)';
          ctx.lineWidth = 1;
          ctx.strokeRect(x, y, cell, cell);
        }
      }

      if (opts.hint && opts.selected) {
        const targets = legal.filter(m => m.fromRow === opts.selected.row && m.fromCol === opts.selected.col);
        for (const move of targets) {
          const cx = pad + move.toCol * cell + cell / 2;
          const cy = pad + move.toRow * cell + cell / 2;
          ctx.beginPath();
          ctx.arc(cx, cy, move.capture ? cell * .24 : cell * .16, 0, Math.PI * 2);
          ctx.fillStyle = move.capture ? 'rgba(239,75,64,.55)' : 'rgba(75,214,133,.45)';
          ctx.fill();
          ctx.strokeStyle = move.jump ? 'rgba(130,220,255,.95)' : 'rgba(255,244,190,.8)';
          ctx.lineWidth = move.jump ? 3 : 1.5;
          ctx.stroke();
        }
      }

      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const v = board[r][c];
          if (!v) continue;
          const side = Math.floor(v / 10);
          const type = v % 10;
          const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
          this.drawPiece(ctx, pad + c * cell + cell / 2, pad + r * cell + cell / 2,
            cell * .37, side, type, isLast);
        }
      }

      if (opts.selected) {
        const x = pad + opts.selected.col * cell, y = pad + opts.selected.row * cell;
        ctx.strokeStyle = '#ffd34e';
        ctx.lineWidth = 4;
        ctx.strokeRect(x + 3, y + 3, cell - 6, cell - 6);
      }
    },

    drawPiece(ctx, x, y, r, side, type, isLast) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,.48)';
      ctx.shadowBlur = 7;
      ctx.shadowOffsetY = 3;
      const grad = ctx.createRadialGradient(x - r * .35, y - r * .4, 0, x, y, r);
      if (side === 1) {
        grad.addColorStop(0, '#ffd7be');
        grad.addColorStop(.55, '#d86b4e');
        grad.addColorStop(1, '#8b241c');
      } else {
        grad.addColorStop(0, '#d9dfea');
        grad.addColorStop(.55, '#43516b');
        grad.addColorStop(1, '#151d2c');
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      ctx.strokeStyle = side === 1 ? '#7a1715' : '#0a1220';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, r * .9, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = side === 1 ? '#fff3da' : '#f6f3e9';
      ctx.font = `bold ${Math.round(r * .92)}px Noto Serif SC, serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(JUNGLE_GLYPHS[type] || '?', x, y);
      ctx.fillStyle = side === 1 ? '#85251f' : '#182235';
      ctx.beginPath();
      ctx.arc(x + r * .62, y + r * .62, r * .34, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.font = `bold ${Math.round(r * .34)}px monospace`;
      ctx.fillText(String(type || ''), x + r * .62, y + r * .64);
      if (isLast) {
        ctx.strokeStyle = '#ffdf6b';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r + 3, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my) {
      const col = Math.floor((mx - this.padding) / this.cell);
      const row = Math.floor((my - this.padding) / this.cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },

    drawGhost() {},
    isLegal(board, row, col) { return !!board[row] && board[row][col] === 0; },
    legalMoves() { return []; },

    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) board[e[0]][e[1]] = e[2];
      return null;
    }
  };

  // ============================================================
  // 暗棋（4×8，全部背面随机开局，翻子/走子二选一）
  // ============================================================
  // 客户端只收到“背面=9”或已翻开的棋子；真实身份由服务端在翻子时公开。
  const DARK_CHESS_GLYPHS = { 1: '帅', 2: '仕', 3: '相', 4: '车', 5: '马', 6: '炮', 7: '兵' };
  const DARK_CHESS_BLACK = { 1: '将', 2: '士', 3: '象', 4: '车', 5: '马', 6: '炮', 7: '卒' };
  const DARK_CHESS_HIDDEN = 9;

  const DARK_CHESS = {
    name: '暗棋',
    rows: 4, cols: 8,
    cell: 78, padding: 30,
    stepTime: 30, totalTime: 600,
    footer: '4×8 暗棋 · 全部背面随机开局 · 翻子或走子 · 帅>仕>相>车>马>炮>兵',
    rules: {
      goal: '吃光对方棋子，或让对方无子可翻、无子可走。',
      steps: [
        '开局 32 枚棋子全部背面朝上、随机落位；暗子的归属和身份都不可见，红方先手。',
        '轮到你时二选一：翻开任意一枚背面棋子，或移动一枚已翻开的己方棋子。',
        '走法：帅一步直线，仕一步斜线，相两步斜线且象眼不能有子，车直线任意距离，马走日且不能蹩腿，炮走直线但吃子必须隔一枚炮架，兵只能向前一步。',
        '吃子按大小：帅 > 仕 > 相 > 车 > 马 > 炮 > 兵；兵可以吃帅，帅不能吃兵。炮吃子不受大小限制；背面棋子可以被任何棋子直接吃掉。',
        '对方棋子被吃光，或对方无子可翻、无子可走即获胜；连续 120 个无吃子且无翻子的半回合判和。'
      ],
      tips: ['先翻离对方明棋较远的暗子，减少刚翻开就被吃掉的损失。', '炮需要炮架才能吃子，保留一枚炮往往能在中盘翻盘。']
    },
    icon: '🀄',
    moveSelect: true,
    usePieceValues: true,
    sides: [{ icon: 'red', label: '红' }, { icon: 'black', label: '黑' }],

    canvasWidth() { return this.padding * 2 + this.cell * this.cols; },
    canvasHeight() { return this.padding * 2 + this.cell * this.rows; },

    initialBoard() {
      const b = emptyBoard(this.rows, this.cols);
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) b[r][c] = DARK_CHESS_HIDDEN;
      }
      return b;
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();
      const view = opts.view || {};
      const legal = Array.isArray(view.legalMoves) ? view.legalMoves : [];
      const flips = Array.isArray(view.legalFlips) ? view.legalFlips : [];
      const hint = !!opts.hint;

      const bg = ctx.createLinearGradient(0, 0, W, H);
      bg.addColorStop(0, '#2c1f18');
      bg.addColorStop(.5, '#3b2a20');
      bg.addColorStop(1, '#1d1511');
      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, W, H);

      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const x = pad + c * cell, y = pad + r * cell;
          ctx.fillStyle = (r + c) % 2 ? '#d8bc86' : '#e7d2a4';
          ctx.fillRect(x, y, cell, cell);
          if (hint && !board[r][c] && legal.some(m => m.toRow === r && m.toCol === c)) {
            ctx.fillStyle = 'rgba(58,160,108,.32)';
            ctx.fillRect(x + 4, y + 4, cell - 8, cell - 8);
          }
          if (hint && board[r][c] === DARK_CHESS_HIDDEN
              && flips.some(f => f.row === r && f.col === c)) {
            ctx.fillStyle = 'rgba(255,211,90,.20)';
            ctx.fillRect(x + 3, y + 3, cell - 6, cell - 6);
            ctx.strokeStyle = 'rgba(255,211,90,.75)';
            ctx.lineWidth = 2;
            ctx.setLineDash([5, 4]);
            ctx.strokeRect(x + 4, y + 4, cell - 8, cell - 8);
            ctx.setLineDash([]);
          }
          ctx.strokeStyle = 'rgba(88,60,32,.42)';
          ctx.lineWidth = 1;
          ctx.strokeRect(x, y, cell, cell);
        }
      }

      // 吃子/走子提示
      if (hint && opts.selected) {
        const targets = legal.filter(m => m.fromRow === opts.selected.row && m.fromCol === opts.selected.col);
        for (const move of targets) {
          const cx = pad + move.toCol * cell + cell / 2;
          const cy = pad + move.toRow * cell + cell / 2;
          ctx.beginPath();
          ctx.arc(cx, cy, move.capture ? cell * .26 : cell * .15, 0, Math.PI * 2);
          ctx.fillStyle = move.capture ? 'rgba(214,66,52,.55)' : 'rgba(58,160,108,.48)';
          ctx.fill();
          ctx.strokeStyle = 'rgba(255,240,200,.86)';
          ctx.lineWidth = 1.5;
          ctx.stroke();
        }
      }

      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const v = board[r][c];
          if (!v) continue;
          const x = pad + c * cell + cell / 2;
          const y = pad + r * cell + cell / 2;
          const isLast = opts.lastMove && opts.lastMove[0] === r && opts.lastMove[1] === c;
          if (v === DARK_CHESS_HIDDEN) {
            this.drawBack(ctx, x, y, cell * .38, isLast);
          } else {
            const side = Math.floor(v / 10);
            const type = v % 10;
            this.drawPiece(ctx, x, y, cell * .38, side, type, isLast);
          }
        }
      }

      if (opts.selected) {
        const x = pad + opts.selected.col * cell, y = pad + opts.selected.row * cell;
        ctx.strokeStyle = '#ffd34e';
        ctx.lineWidth = 4;
        ctx.strokeRect(x + 3, y + 3, cell - 6, cell - 6);
      }

      const hiddenCount = typeof view.hiddenCount === 'number'
        ? view.hiddenCount : board.flat().filter(v => v === DARK_CHESS_HIDDEN).length;
      ctx.fillStyle = 'rgba(255,235,196,.82)';
      ctx.font = 'bold 13px JetBrains Mono, monospace';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillText('暗子 ' + hiddenCount, pad, pad / 2);
      ctx.textAlign = 'right';
      ctx.fillText('红 ' + (view.redRemaining ?? '—') + ' · 黑 ' + (view.blackRemaining ?? '—'), W - pad, pad / 2);
      ctx.textAlign = 'start';
      ctx.textBaseline = 'alphabetic';
    },

    drawBack(ctx, x, y, r, isLast) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,.5)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetY = 3;
      const grad = ctx.createRadialGradient(x - r * .35, y - r * .4, 0, x, y, r);
      grad.addColorStop(0, '#7a4f36');
      grad.addColorStop(.55, '#4b2d1e');
      grad.addColorStop(1, '#24140d');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      ctx.strokeStyle = 'rgba(232,197,140,.55)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, r * .82, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = 'rgba(238,207,155,.55)';
      ctx.font = `bold ${Math.round(r * .8)}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('棋', x, y + 1);
      if (isLast) {
        ctx.strokeStyle = '#ffdf6b';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r + 3, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    drawPiece(ctx, x, y, r, side, type, isLast) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,.5)';
      ctx.shadowBlur = 6;
      ctx.shadowOffsetY = 3;
      const grad = ctx.createRadialGradient(x - r * .35, y - r * .4, 0, x, y, r);
      if (side === 1) {
        grad.addColorStop(0, '#ffe0c8');
        grad.addColorStop(.55, '#d4664b');
        grad.addColorStop(1, '#89251d');
      } else {
        grad.addColorStop(0, '#dfe5ef');
        grad.addColorStop(.55, '#44526c');
        grad.addColorStop(1, '#161e2c');
      }
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      ctx.strokeStyle = side === 1 ? '#7a1715' : '#0a1220';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, r * .9, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = side === 1 ? '#fff4e2' : '#f5f2e8';
      ctx.font = `bold ${Math.round(r * .9)}px Noto Serif SC, serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const glyphs = side === 1 ? DARK_CHESS_GLYPHS : DARK_CHESS_BLACK;
      ctx.fillText(glyphs[type] || '?', x, y + 1);
      if (isLast) {
        ctx.strokeStyle = '#ffdf6b';
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(x, y, r + 3, 0, Math.PI * 2);
        ctx.stroke();
      }
    },

    hitTest(mx, my) {
      const col = Math.floor((mx - this.padding) / this.cell);
      const row = Math.floor((my - this.padding) / this.cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },

    drawGhost() {},

    // 本地粗校验：空位或对方明棋可走；实际合法性完全以服务端 legalMoves / legalFlips 为准
    isLegal(board, row, col, side) {
      const v = board[row] ? board[row][col] : 0;
      if (!v) return true;
      if (v === DARK_CHESS_HIDDEN) return true;
      return Math.floor(v / 10) !== side;
    },

    legalMoves() { return []; },

    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) board[e[0]][e[1]] = e[2];
      return null;
    }
  };

  // ============================================================
  // 飞行棋（15×15 外圈 56 格 + 中心终点）
  // ============================================================
  const FLIGHT_TRACK = [];
  for (let c = 0; c < 15; c++) FLIGHT_TRACK.push([0, c]);
  for (let r = 1; r < 15; r++) FLIGHT_TRACK.push([r, 14]);
  for (let c = 13; c >= 0; c--) FLIGHT_TRACK.push([14, c]);
  for (let r = 13; r >= 1; r--) FLIGHT_TRACK.push([r, 0]);

  const FLIGHT_BASES = [
    [[1, 1], [1, 2], [2, 1], [2, 2]],
    [[1, 13], [1, 12], [2, 13], [2, 12]],
    [[13, 1], [13, 2], [12, 1], [12, 2]],
    [[13, 13], [13, 12], [12, 13], [12, 12]]
  ];
  const FLIGHT_STARTS = [0, 14, 28, 42];
  const FLIGHT_JUMPS = { 6: 17, 19: 30, 33: 45, 48: 54 };
  const FLIGHT_COLORS = ['#e74c3c', '#f1c40f', '#3498db', '#2ecc71'];
  const FLIGHT_DARK = ['#b03a2e', '#b7950b', '#21618c', '#1e8449'];

  const FLIGHT_CHESS = {
    name: '飞行棋',
    rows: 15, cols: 15, cell: 30, padding: 6,
    stepTime: 30, totalTime: 600,
    footer: '2–4 人 · 掷出 6 点起飞 · 撞回对手 · 四子到终点获胜',
    rules: {
      goal: '先把自己 4 枚棋子全部送进中心终点。',
      steps: [
        '每回合服务端自动掷骰，你只需选择一枚可移动的棋子。掷出 6 点才能让基地中的棋子起飞，并可再掷一次。',
        '棋子沿外圈前进；落在跳格上会额外前进，落在对手棋子上会把它撞回基地。',
        '必须刚好走到中心才算到达，点数超过终点的棋子本回合不能走。',
        '四枚棋子全部到达终点即获胜。'
      ],
      tips: ['优先起飞扩大场上棋子数量。', '快到终点时留意点数，避免超出而停在危险位置。']
    },
    icon: '✈',
    moveSelect: true,
    usePieceValues: true,
    sides: [
      { icon: 'red', label: '红' }, { icon: 'yellow', label: '黄' },
      { icon: 'blue', label: '蓝' }, { icon: 'green', label: '绿' }
    ],
    minPlayers: 2, maxPlayers: 4, defaultPlayers: 2,

    canvasWidth() { return this.padding * 2 + this.cell * this.cols; },
    canvasHeight() { return this.padding * 2 + this.cell * this.rows; },
    initialBoard() {
      const b = emptyBoard(this.rows, this.cols);
      FLIGHT_BASES.forEach((cells, side) => cells.forEach(([r, c], piece) => {
        b[r][c] = (side + 1) * 10 + piece;
      }));
      return b;
    },

    draw(ctx, board, opts) {
      const pad = this.padding, cell = this.cell;
      const W = this.canvasWidth(), H = this.canvasHeight();
      ctx.fillStyle = '#101820';
      ctx.fillRect(0, 0, W, H);

      const view = opts.view || {};
      const turn = Number(view.turn) || opts.currentTurn || 1;
      const activeColor = FLIGHT_COLORS[turn - 1] || '#fff';

      // 四条边的跑道格
      for (let i = 0; i < FLIGHT_TRACK.length; i++) {
        const [r, c] = FLIGHT_TRACK[i];
        const owner = i % 14 === 0 ? Math.floor(i / 14) : -1;
        ctx.fillStyle = owner >= 0 ? FLIGHT_COLORS[owner] + '55' : ((i % 2) ? '#263445' : '#1d2a38');
        ctx.fillRect(pad + c * cell, pad + r * cell, cell, cell);
        ctx.strokeStyle = '#0c131a';
        ctx.strokeRect(pad + c * cell, pad + r * cell, cell, cell);
        if (owner >= 0 && owner + 1 <= (view.playerCount || 4)) {
          ctx.fillStyle = 'rgba(255,255,255,0.65)';
          ctx.font = 'bold 8px sans-serif';
          ctx.textAlign = 'left';
          ctx.textBaseline = 'top';
          ctx.fillText('起', pad + c * cell + 2, pad + r * cell + 2);
        }
        if (FLIGHT_JUMPS[i + 1]) {
          ctx.fillStyle = 'rgba(255,215,100,0.9)';
          ctx.font = 'bold 13px sans-serif';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText('✈', pad + c * cell + cell / 2, pad + r * cell + cell / 2);
        }
      }

      // 四个 2×2 基地：每个棋子独立一格，避免四枚叠在同一个坐标无法点选。
      FLIGHT_BASES.forEach((cells, i) => {
        if (i + 1 > (view.playerCount || 4)) return;
        const minR = Math.min(...cells.map(x => x[0]));
        const minC = Math.min(...cells.map(x => x[1]));
        ctx.fillStyle = FLIGHT_COLORS[i] + '18';
        ctx.fillRect(pad + minC * cell, pad + minR * cell, cell * 2, cell * 2);
        ctx.strokeStyle = FLIGHT_COLORS[i] + (turn === i + 1 ? 'ff' : '88');
        ctx.lineWidth = turn === i + 1 ? 3 : 1.5;
        ctx.strokeRect(pad + minC * cell, pad + minR * cell, cell * 2, cell * 2);
        cells.forEach(([r, c]) => {
          ctx.strokeStyle = FLIGHT_COLORS[i] + '44';
          ctx.lineWidth = 1;
          ctx.strokeRect(pad + c * cell + 3, pad + r * cell + 3, cell - 6, cell - 6);
        });
      });

      // 中心终点
      ctx.fillStyle = '#f6e7b0';
      ctx.fillRect(pad + 7 * cell, pad + 7 * cell, cell, cell);
      ctx.strokeStyle = '#c9a227';
      ctx.strokeRect(pad + 7 * cell, pad + 7 * cell, cell, cell);
      ctx.fillStyle = '#7a5c00';
      ctx.font = 'bold 14px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('终', pad + 7 * cell + cell / 2, pad + 7 * cell + cell / 2);

      // 棋子（同一格可叠多枚）；服务端 players 是完整位置，board 只用于兼容旧快照。
      const stacks = new Map();
      for (let r = 0; r < this.rows; r++) for (let c = 0; c < this.cols; c++) {
        if (!board[r] || !board[r][c]) continue;
        const key = r + ',' + c;
        if (!stacks.has(key)) stacks.set(key, []);
        stacks.get(key).push(board[r][c]);
      }
      if (Array.isArray(view.players)) {
        stacks.clear();
        view.players.forEach(p => {
          const positions = Array.isArray(p.positions) ? p.positions : [];
          positions.forEach((pos, piece) => {
            let r, c;
            if (pos === 0) [r, c] = FLIGHT_BASES[p.side - 1][piece];
            else if (pos >= 57) [r, c] = [7, 7];
            else [r, c] = FLIGHT_TRACK[pos - 1];
            const key = r + ',' + c;
            if (!stacks.has(key)) stacks.set(key, []);
            stacks.get(key).push(p.side * 10 + piece);
          });
        });
      }
      for (const [key, pieces] of stacks) {
        const [r, c] = key.split(',').map(Number);
        pieces.forEach((v, idx) => {
          const side = Math.floor(v / 10);
          const piece = v % 10;
          const cx = pad + c * cell + cell / 2 + (idx % 2) * 6 - 3;
          const cy = pad + r * cell + cell / 2 + Math.floor(idx / 2) * 6 - 3;
          ctx.fillStyle = FLIGHT_COLORS[side - 1] || '#999';
          ctx.beginPath();
          ctx.arc(cx, cy, cell * 0.28, 0, Math.PI * 2);
          ctx.fill();
          ctx.strokeStyle = '#fff';
          ctx.lineWidth = 1.5;
          ctx.stroke();
          ctx.fillStyle = '#111';
          ctx.font = 'bold 9px sans-serif';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(String(piece + 1), cx, cy);
        });
      }

      // 当前玩家可选起点 + 合法目标点提示
      const legal = Array.isArray(view.legalMoves) ? view.legalMoves : [];
      if (opts.hint && legal.length) {
        const starts = new Set();
        const targets = new Set();
        legal.forEach(m => {
          starts.add(m.fromRow + ',' + m.fromCol);
          targets.add(m.toRow + ',' + m.toCol);
        });
        starts.forEach(key => {
          const [r, c] = key.split(',').map(Number);
          ctx.fillStyle = 'rgba(120,220,255,0.2)';
          ctx.fillRect(pad + c * cell + 3, pad + r * cell + 3, cell - 6, cell - 6);
          ctx.strokeStyle = 'rgba(120,220,255,0.9)';
          ctx.lineWidth = 2;
          ctx.strokeRect(pad + c * cell + 3, pad + r * cell + 3, cell - 6, cell - 6);
        });
        targets.forEach(key => {
          const [r, c] = key.split(',').map(Number);
          ctx.strokeStyle = 'rgba(255,215,80,0.95)';
          ctx.lineWidth = 3;
          ctx.strokeRect(pad + c * cell + 2, pad + r * cell + 2, cell - 4, cell - 4);
        });
      }
      if (opts.selected) {
        ctx.strokeStyle = 'rgba(255,255,255,0.95)';
        ctx.lineWidth = 3;
        ctx.strokeRect(pad + opts.selected.col * cell + 1, pad + opts.selected.row * cell + 1, cell - 2, cell - 2);
      }

      // 顶部行动条：骰子、阶段和当前颜色，手机端也能一眼看懂该做什么。
      const phase = view.phase || (view.over ? 'FINISHED' : 'MOVE');
      const phaseText = view.over ? '本局结束'
        : phase === 'SKIP' ? (myTurnText(view, opts) ? '无棋可走 · 点击跳过' : '等待跳过')
        : phase === 'ROLLING' ? '等待掷骰'
        : (myTurnText(view, opts) ? '选择棋子，再点高亮落点' : '等待对手走子');
      ctx.fillStyle = 'rgba(6,12,18,0.92)';
      ctx.fillRect(pad + 3 * cell, pad + 3 * cell, 9 * cell, 1.15 * cell);
      ctx.strokeStyle = activeColor;
      ctx.lineWidth = 1;
      ctx.strokeRect(pad + 3 * cell, pad + 3 * cell, 9 * cell, 1.15 * cell);
      ctx.fillStyle = activeColor;
      ctx.font = 'bold 16px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('🎲 ' + (view.dice || '—'), pad + 5 * cell, pad + 3.575 * cell);
      ctx.fillStyle = '#f4ecd8';
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText(phaseText, pad + 6.7 * cell, pad + 3.58 * cell);

      // 服务端最近一手的规则事件：到达终点或触发跳格时在盘面直接提示。
      const finishedPiece = Number(view.lastFinishedPiece);
      if (finishedPiece >= 0 || Number(view.lastJumpedFrom) > 0) {
        const eventText = finishedPiece >= 0
          ? '第 ' + (finishedPiece + 1) + ' 枚棋子到达终点'
          : '跳格 ' + view.lastJumpedFrom + ' → ' + view.lastJumpedTo;
        ctx.fillStyle = 'rgba(246,231,176,0.14)';
        ctx.fillRect(pad + 3 * cell, pad + 4.25 * cell, 9 * cell, 0.8 * cell);
        ctx.fillStyle = '#f6e7b0';
        ctx.font = 'bold 11px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(eventText, pad + 7.5 * cell, pad + 4.66 * cell);
      }
    },

    hitTest(mx, my) {
      const pad = this.padding, cell = this.cell;
      const col = Math.floor((mx - pad) / cell);
      const row = Math.floor((my - pad) / cell);
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return null;
      return { row, col };
    },
    drawGhost() {},
    isLegal(board, row, col, side) {
      const v = board?.[row]?.[col] || 0;
      return v !== 0 && Math.floor(v / 10) === side;
    },
    legalMoves() { return []; },
    applyEffects(board, effects) {
      if (!effects) return null;
      for (const e of effects) {
        if (board[e[0]]) board[e[0]][e[1]] = e[2];
      }
      return null;
    }
  };

  function myTurnText(view, opts) {
    return opts.myStone > 0 && Number(view.turn) === Number(opts.myStone);
  }

  // ============================================================
  // 四子棋
  // ============================================================
  const CONNECT4 = {
    name: '四子棋',
    rows: 6, cols: 7,
    cell: 60, padding: 10,
    stepTime: 30, totalTime: 300,
    footer: '7×6 四子棋 · 横竖斜四子连线获胜 · 落子下坠',
    rules: {
      goal: '在横、竖或斜线上先连成四颗同色棋子。',
      steps: [
        '红方先手。选择一列落子，棋子会落到该列最低的空位。',
        '任意方向连成四子即获胜；棋盘填满仍无人连成四子则平局。'
      ],
      tips: ['同时制造两个威胁，对手只能挡一边。', '注意斜线，四子棋的胜负经常在斜线上完成。']
    },
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
    rules: {
      goal: '把对手的生命值降到 0。',
      steps: [
        '双方各有 100 生命、40 法力，每回合自动回复 3 点法力。',
        '每回合选择普通攻击、强力一击、火球术、治疗术、防御或喝药水；药水每局 2 瓶。',
        '火球术附加 2 回合灼烧，防御可减免本回合伤害并回蓝。',
        '第 25 回合进入决战阶段：伤害提升、治疗效果减半，避免无限治疗。'
      ],
      tips: ['法力留给斩杀或关键治疗，不要每回合都把蓝打空。', '血量低时先防御拖到对手爆发结束。']
    },
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
    rules: {
      goal: '把对方英雄的 30 点生命降到 0。',
      steps: [
        '双方各有一套 30 张牌库，起手摸牌并保证至少一张可打；每回合抽 1 张。',
        '法力上限每回合 +1（最高 10），开局有 3 点法力；手牌上限 7、场上随从上限 7。',
        '打出随从、法术或治疗牌；随从除冲锋外召唤当回合不能攻击。',
        '点「结束回合」交出行动权；牌库抽空后会受到递增的疲劳伤害。'
      ],
      tips: ['嘲讽随从能保护英雄，圣盾可抵挡一次伤害。', '留意对手法力，判断它下回合能否完成斩杀。']
    },
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
    rules: {
      goal: '拿走最后一颗石子。',
      steps: [
        '三堆石子初始为 5、7、9 颗。',
        '轮到你时选择一堆，拿走任意正数颗，不能超过该堆剩余数量。',
        '拿走最后一颗石子的人获胜；三堆全空时当前行动方失败。'
      ],
      tips: ['试着让三堆数量的异或和为 0，这是必败态留给对手。', '不要只盯一堆，三堆要一起算。']
    },
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
    rules: {
      goal: '8 道题结束后比分更高。',
      steps: [
        '每局从科学、人文、技术、地理、历史、生活等主题轮转抽 8 题。',
        '双方看到同一道题，各自选择 A–D；都提交前互相看不到对方选择，也看不到答案。',
        '答对 +10 分，答错 -5 分（最低 0 分）。',
        '连续答对从第二题起额外 +5 分，答错会清空连击。'
      ],
      tips: ['不确定时先排除明显错误选项。', '连击奖励很高，保持稳定比抢答更重要。']
    },
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
    rules: {
      goal: '六轮结束后累计分数最高。',
      steps: [
        '每轮所有人同时选择一种策略，全部提交后一起揭晓。',
        '稳健：掷 1 颗骰子，点数即得分。',
        '冒险：掷 2 颗骰子，点数相加；只要出现 1 就爆掉，本轮 0 分。',
        '追击：掷 1 颗骰子；若当前落后领先者则 +3，否则 -1（最低 0 分）。'
      ],
      tips: ['落后时追击有额外收益，但也可能被反超。', '最后一轮按当前总分判断，不必留牌。']
    },
    actionLabels: ['稳健', '冒险', '追击'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const BLACKJACK = {
    name: '21 点', html: true, panel: 'blackjack-panel', render: 'renderBlackjackPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '🂡',
    footer: '2–8 人 · 依次要牌/停牌 · 庄家 17 点停牌 · 爆牌即负',
    rules: {
      goal: '让自己的点数尽量接近 21，同时不要超过 21。',
      steps: [
        '每人先发两张牌，庄家一张明牌一张暗牌；使用多副牌洗牌。',
        '轮到你时可选择「要牌」或「停牌」；A 可算 1 或 11，J/Q/K 算 10。',
        '超过 21 点即爆牌判负；正好 21 点自动停牌。',
        '所有人停牌后庄家揭示暗牌，庄家 17 点及以上停牌；点数高者胜，同点平局。'
      ],
      tips: ['庄家明牌是 7 以上时，自己 12–16 点要更谨慎。', 'A 加 10 点的黑杰克是最强起手。']
    },
    actionLabels: ['要牌', '停牌'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const GEM_AUCTION = {
    name: '宝石拍卖', html: true, panel: 'gem-auction-panel', render: 'renderGemAuctionPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '💎',
    footer: '2–8 人 · 暗投金币 · 五轮争夺 3/5/7/9/12 分宝石',
    rules: {
      goal: '五轮拍卖后累计宝石分最高。',
      steps: [
        '每人初始 30 金币；每轮从 0–10 金币中暗投一个出价，不能超过余额。',
        '所有人提交后揭晓：最高出价者获得本轮宝石分，并列最高价都获得同一份宝石分。',
        '所有出价都会扣除金币，未中标也消耗；五轮宝石分值依次为 3、5、7、9、12。',
        '五轮结束按累计宝石分排名，同分则平局。'
      ],
      tips: ['前期留金币，最后一轮 12 分宝石往往决定胜负。', '并列最高价会平分荣誉但各拿全分，抬价要算成本。']
    },
    actionLabels: ['出价 0', '出价 1', '出价 2', '出价 3', '出价 4', '出价 5', '出价 6', '出价 7', '出价 8', '出价 9', '出价 10'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const HIGH_CARD = {
    name: '比大小', html: true, panel: 'high-card-panel', render: 'renderHighCardPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 300, icon: '♠',
    footer: '2–8 人 · 五轮暗选 · 一次王牌翻倍 · 决胜轮双倍计分',
    rules: {
      goal: '五轮结束后累计分数最高。',
      steps: [
        '每人手中有 1–10 的牌，每轮同时暗选一张，全部提交后揭晓。',
        '本轮最大牌得 1 分，并列最大都得分；第五轮决胜轮得 2 分。',
        '每局有一次「王牌」机会：指定一张牌使其牌值翻倍，使用后本局不能再使用。'
      ],
      tips: ['前几轮不必过早交大牌，决胜轮双倍计分更关键。', '王牌翻倍后仍可能被更大的牌压过，算好剩余牌。']
    },
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const UNO = {
    name: 'UNO 对战', html: true, panel: 'uno-panel', render: 'renderUnoPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4, stepTime: 30, totalTime: 300, icon: '🃏',
    footer: '2–8 人 · 按颜色或数字出牌 · 无牌可出时摸牌',
    rules: {
      goal: '最先清空手牌，并按对手剩余手牌获得分数。',
      steps: [
        '每人起手 7 张牌，按颜色或数字与顶牌匹配即可出牌。',
        '无牌可出时摸一张；摸到可出的牌可以立即打出，也可以结束摸牌。',
        '特殊牌：0 轮转所有手牌，7 与指定玩家交换手牌，10 跳过下一位，11 反转方向，+2 让下一位摸牌，万能牌可指定颜色，+4 仅在没有同色牌时可用。',
        '只剩 1 张牌时必须「喊 UNO」；忘记喊会被其他玩家抓牌罚摸 2 张。',
        '清空手牌即获胜，按其他玩家剩余手牌的点数计分。'
      ],
      tips: ['保留一张万能牌处理卡手局面。', '只剩一张时先喊 UNO，再考虑出牌。']
    },
    actionLabels: ['摸一张'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; }, drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  const BOMB_CAT = {
    name: '炸弹猫', html: true, panel: 'bomb-cat-panel', render: 'renderBombCatPanel',
    minPlayers: 2, maxPlayers: 8, defaultPlayers: 4,
    stepTime: 30, totalTime: 600, icon: '💣',
    footer: '2–8 人 · 出牌后抽牌 · 拆弹器救命 · 最后存活者胜',
    rules: {
      goal: '最后存活下来。抽到炸弹时没有拆弹器就会出局。',
      steps: [
        '每人起手 4 张行动牌和 1 张拆弹器；牌库中混有炸弹。',
        '轮到你时可先打出任意数量行动牌，最后点「抽一张」结束回合。',
        '跳过：下一位玩家被跳过；攻击：下一位玩家连续行动两回合。',
        '预知：只给自己查看牌库顶 3 张；洗牌会打乱牌库；偷牌从指定玩家手中随机拿走一张，双方都不知道偷到的是什么。',
        '抽到炸弹：有拆弹器可消耗它，并秘密选择把炸弹放回牌库顶、中间或底部；没有拆弹器则出局。'
      ],
      tips: [
        '有拆弹器并不等于安全，攻击会让下家连续承担两次抽牌风险。',
        '预知后若看到危险，优先用跳过或攻击改变顺序，再决定何时抽牌。',
        '偷牌会隐藏牌的身份，关注对手手牌数量比猜测具体牌更可靠。'
      ]
    },
    actionLabels: ['拆弹器', '跳过', '攻击', '预知', '洗牌', '偷牌', '炸弹'],
    initialBoard() { return null; }, draw() {}, hitTest() { return null; },
    drawGhost() {}, isLegal() { return false; }, applyEffects() {}, legalMoves() { return []; }
  };

  window.GameModules = { HIGH_CARD, BLACKJACK, BOMB_CAT, GOMOKU, REVERSI, GO9, XIANGQI, CHECKERS, JUNGLE, DARK_CHESS, FLIGHT_CHESS, CONNECT4, RPG, CARD, NIM, QUIZ, DICE_RACE, GEM_AUCTION, UNO };
})();

