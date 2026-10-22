// Draws the mod icon as 32x32 pixel art and writes it as PNG, scaled by an integer factor (nearest neighbour).
// node icon.js <out.png> <scale>
const fs = require('fs');
const zlib = require('zlib');

const N = 32;
const px = Array.from({length: N}, () => Array(N).fill(null));
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
function set(x, y, c) {
	if (x >= 0 && y >= 0 && x < N && y < N) px[y][x] = typeof c === 'string' ? hex(c) : c;
}
function rect(x0, y0, x1, y1, c) {
	for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) set(x, y, c);
}

const OUTLINE = '#141414';
const BODY = '#2a2a2a';
const WHITE = '#f2f2f2';
const GREY = '#bdbdbd';

// --- the clapper stick, hinged at the left and lifted to the right ---
// a parallelogram 5 px thick, rising 1 px every 4 px
for (let x = 2; x <= 29; x++) {
	const lift = Math.floor((x - 2) / 4);
	const top = 8 - lift;
	for (let t = 0; t < 5; t++) {
		const y = top + t;
		const edge = t === 0 || t === 4 || x === 2 || x === 29;
		let c;
		if (edge) {
			c = OUTLINE;
		} else {
			// diagonal stripes
			c = Math.floor((x + t) / 4) % 2 === 0 ? WHITE : BODY;
		}
		set(x, y, c);
	}
}
// the hinge
rect(2, 11, 4, 13, OUTLINE);
set(3, 12, GREY);

// --- the board ---
rect(2, 13, 29, 29, OUTLINE);
rect(3, 14, 28, 28, BODY);
// stripes on the board's top band
for (let x = 3; x <= 28; x++) {
	for (let y = 14; y <= 16; y++) {
		set(x, y, Math.floor((x + (y - 14)) / 4) % 2 === 0 ? WHITE : BODY);
	}
}
rect(3, 17, 28, 17, OUTLINE);

// --- the picture on the board: a little survival world ---
const SKY_TOP = '#5e9cf0', SKY_LOW = '#9cc8ff';
for (let y = 18; y <= 27; y++) {
	for (let x = 4; x <= 27; x++) {
		set(x, y, y <= 21 ? SKY_TOP : SKY_LOW);
	}
}
// sun
rect(6, 19, 8, 21, '#ffe066');
set(6, 19, '#ffd23f'); set(8, 21, '#ffd23f');
// a cloud
rect(12, 19, 15, 19, '#ffffff');
rect(11, 20, 17, 20, '#f0f6ff');
// hills: far (dark) and near (light) grass
const FAR = '#3f7f2a', NEAR = '#5fae36', DIRT = '#8a5a2b', DIRT_DARK = '#6b4421';
const far = [24, 24, 23, 23, 22, 22, 22, 23, 23, 24, 24, 24, 24, 23, 23, 22, 22, 21, 21, 22, 22, 23, 23, 24];
for (let i = 0; i < far.length; i++) {
	for (let y = far[i]; y <= 27; y++) set(4 + i, y, FAR);
}
const near = [25, 25, 25, 25, 24, 24, 24, 24, 25, 25, 25, 25, 25, 25, 26, 26, 26, 26, 25, 25, 25, 25, 25, 25];
for (let i = 0; i < near.length; i++) {
	set(4 + i, near[i], NEAR);
	for (let y = near[i] + 1; y <= 27; y++) set(4 + i, y, (i + y) % 3 === 0 ? DIRT_DARK : DIRT);
}
// a tree on the far hill
rect(20, 18, 22, 20, '#2f6a1f');
set(21, 18, '#3d8a28');
set(21, 21, '#5b3a1a');
// the recording dot
rect(25, 19, 26, 20, '#e8232b');
set(25, 19, '#ff8080');

// --- write PNG ---
function crc32(buf) {
	let c, crc = 0xffffffff;
	for (let n = 0; n < buf.length; n++) {
		c = (crc ^ buf[n]) & 0xff;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		crc = (crc >>> 8) ^ c;
	}
	return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const td = Buffer.concat([Buffer.from(type), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(td));
	return Buffer.concat([len, td, crc]);
}
const out = process.argv[2];
const scale = parseInt(process.argv[3] || '1', 10);
const size = N * scale;
const raw = Buffer.alloc((size * 4 + 1) * size);
for (let y = 0; y < size; y++) {
	raw[y * (size * 4 + 1)] = 0;
	for (let x = 0; x < size; x++) {
		const p = px[Math.floor(y / scale)][Math.floor(x / scale)] || [0, 0, 0, 0];
		const o = y * (size * 4 + 1) + 1 + x * 4;
		raw[o] = p[0]; raw[o + 1] = p[1]; raw[o + 2] = p[2]; raw[o + 3] = p[3];
	}
}
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(size, 0);
ihdr.writeUInt32BE(size, 4);
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
	chunk('IDAT', zlib.deflateSync(raw, {level: 9})), chunk('IEND', Buffer.alloc(0))]);
fs.writeFileSync(out, png);
console.log('wrote', out, size + 'x' + size);
