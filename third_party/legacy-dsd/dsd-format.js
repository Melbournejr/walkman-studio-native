/** Bounded DSF / uncompressed DSDIFF reading and genuine low-pass DSD-to-PCM decoding. */
const OUTPUT_RATE = 88200;
const MAX_REQUEST_FRAMES = 262144;
const MAX_READ_BYTES = 32 * 1024 * 1024;
const SILENCE_BYTE = 0x99; // Equal positive/negative DSD bits; no DC component.
const tables = new Map();

function fail(message, code = 'INVALID_DSD') {
  const error = new Error(message);
  error.code = code;
  throw error;
}
function sourceSize(source) {
  const size = Number(source?.size);
  if (!Number.isSafeInteger(size) || size < 28) fail('Tệp DSD quá ngắn hoặc có kích thước không hợp lệ.');
  return size;
}
function isBlob(source) { return typeof source?.slice === 'function' && typeof source?.arrayBuffer === 'function'; }
function text(bytes, offset, count) { return String.fromCharCode(...bytes.subarray(offset, offset + count)); }
function uint64(view, offset, littleEndian) {
  const n = view.getBigUint64(offset, littleEndian);
  if (n > BigInt(Number.MAX_SAFE_INTEGER)) fail('Giá trị kích thước trong tệp DSD vượt giới hạn hỗ trợ.');
  return Number(n);
}
function view(bytes) { return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); }
function validRange(offset, length, limit) {
  return Number.isSafeInteger(offset) && Number.isSafeInteger(length) && offset >= 0 && length >= 0 && offset <= limit && length <= limit - offset;
}
async function read(source, offset, length) {
  const size = sourceSize(source);
  if (!validRange(offset, length, size) || length > MAX_READ_BYTES) fail('Khối dữ liệu DSD vượt phạm vi tệp.');
  if (length === 0) return new Uint8Array(0);
  let data;
  if (isBlob(source)) data = new Uint8Array(await source.slice(offset, offset + length).arrayBuffer());
  else {
    if (typeof source.url !== 'string') fail('Nguồn DSD không có tệp hoặc địa chỉ đọc hợp lệ.');
    let requestURL = source.url;
    let androidChunk = false;
    try {
      const url = new URL(source.url);
      androidChunk = url.protocol === 'https:' && url.host === 'appassets.androidplatform.net' && /^\/music\/native-[a-f0-9]{64}$/.test(url.pathname);
      if (androidChunk) {
        url.searchParams.set('offset', String(offset));
        url.searchParams.set('length', String(length));
        requestURL = url.href;
      }
    } catch { /* Other sources retain the standard byte-range path. */ }
    const response = await fetch(requestURL, androidChunk ? {cache:'no-store'} : {headers:{Range:`bytes=${offset}-${offset + length - 1}`}});
    const declaredLength = response.headers.get('Content-Length');
    if (androidChunk) {
      const actualOffset = response.headers.get('X-Walkman-Byte-Offset');
      const actualLength = response.headers.get('X-Walkman-Byte-Length');
      const actualSize = response.headers.get('X-Walkman-File-Size');
      if (response.status !== 200 || actualOffset === null || actualLength === null || Number(actualOffset) !== offset || Number(actualLength) !== length) {
        await response.body?.cancel();
        fail('Không đọc được khối DSD trên Android. Hãy cập nhật ứng dụng và quét lại thư mục.', 'ANDROID_CHUNK_UNAVAILABLE');
      }
      if (actualSize === null || Number(actualSize) !== size) {
        await response.body?.cancel();
        fail('Kích thước tệp đã thay đổi. Hãy quét lại thư mục nhạc rồi thử lại.', 'SOURCE_CHANGED');
      }
      // Android WebView may rewrite standard Content-Length on intercepted media.
      // The scoped native chunk route attests offset/length; actual body bytes are
      // still strictly bounded and verified below. Never use full-file fallback.
      if (!response.body?.getReader && declaredLength !== null && Number(declaredLength) !== length) {
        await response.body?.cancel();
        fail('Hãy cập nhật Android System WebView để đọc DSD theo từng khối.', 'STREAMING_UNAVAILABLE');
      }
    } else {
      if (response.status !== 206) {
        if (!(response.status === 200 && offset === 0 && length === size && size <= MAX_READ_BYTES))
          fail('Nguồn nhạc chưa hỗ trợ đọc từng phần; không thể đọc DSD an toàn.', 'RANGE_UNAVAILABLE');
      } else {
        const contentRange = response.headers.get('Content-Range');
        if (contentRange && !contentRange.startsWith(`bytes ${offset}-${offset + length - 1}/`))
          fail('Máy chủ trả sai vị trí dữ liệu DSD.', 'RANGE_UNAVAILABLE');
      }
      if (declaredLength !== null && Number(declaredLength) !== length) {
        await response.body?.cancel();
        fail('Nguồn nhạc trả sai kích thước khối DSD.', 'RANGE_UNAVAILABLE');
      }
    }
    if (response.body?.getReader) {
      const reader = response.body.getReader();
      data = new Uint8Array(length);
      let filled = 0;
      try {
        while (true) {
          const part = await reader.read();
          if (part.done) break;
          if (part.value.byteLength > length - filled) {
            await reader.cancel();
            fail('Nguồn nhạc trả quá nhiều dữ liệu cho khối DSD.', 'RANGE_UNAVAILABLE');
          }
          data.set(part.value, filled); filled += part.value.byteLength;
        }
        if (filled !== length) fail('Tệp DSD bị thiếu dữ liệu.');
      } finally { reader.releaseLock(); }
    } else data = new Uint8Array(await response.arrayBuffer());
  }
  if (data.byteLength !== length) fail('Tệp DSD bị thiếu dữ liệu.');
  return data;
}
function finishMetadata(meta) {
  if (meta.channels !== 1 && meta.channels !== 2)
    fail(`DSD ${meta.channels} kênh chưa được hỗ trợ; hãy dùng bản mono hoặc stereo.`, 'UNSUPPORTED_CHANNELS');
  const ratio = meta.sampleRate / OUTPUT_RATE;
  if (![32, 64, 128].includes(ratio))
    fail('Hiện hỗ trợ DSD64, DSD128 và DSD256 thuộc họ tần số 44,1 kHz.', 'UNSUPPORTED_DSD_RATE');
  if (!Number.isSafeInteger(meta.sampleCount) || meta.sampleCount < 1) fail('Tệp DSD không có mẫu âm thanh hợp lệ.');
  return { ...meta, pcmSampleRate: OUTPUT_RATE, decimation: ratio,
    duration: meta.sampleCount / meta.sampleRate,
    totalPCMFrames: Math.ceil(meta.sampleCount / ratio),
    bitDepth: 1, conversion: 'DSD → PCM · FIR 88.2 kHz',
    dsdRate: `DSD${meta.sampleRate / 44100}` };
}
async function inspectDSF(source, initial) {
  const size = sourceSize(source);
  const header = view(initial);
  if (uint64(header, 4, true) !== 28) fail('Header DSF không hợp lệ.');
  const declaredSize = uint64(header, 12, true);
  if (declaredSize < 28 || declaredSize > size) fail('Tệp DSF bị cắt ngắn hoặc khai báo sai kích thước.');
  let offset = 28, format = null, data = null;
  for (let chunks = 0; offset + 12 <= declaredSize && chunks < 10000; chunks++) {
    const chunk = await read(source, offset, 12);
    const id = text(chunk, 0, 4);
    const length = uint64(view(chunk), 4, true);
    if (length < 12 || !validRange(offset, length, declaredSize)) fail('Chunk DSF bị hỏng hoặc vượt phạm vi tệp.');
    if (id === 'fmt ') {
      if (format || length < 52) fail('Chunk định dạng DSF không hợp lệ.');
      const bytes = await read(source, offset, 52), fmt = view(bytes);
      if (fmt.getUint32(12, true) !== 1 || fmt.getUint32(16, true) !== 0)
        fail('Phiên bản hoặc kiểu nén DSF chưa được hỗ trợ.', 'UNSUPPORTED_DSF');
      const bits = fmt.getUint32(32, true);
      if (bits !== 1 && bits !== 8) fail('Thứ tự bit DSF không hợp lệ.');
      const blockSize = fmt.getUint32(44, true);
      if (blockSize < 1 || blockSize > 1024 * 1024) fail('Kích thước block DSF không hợp lệ.');
      format = {channels: fmt.getUint32(24, true), sampleRate: fmt.getUint32(28, true),
        sampleCount: uint64(fmt, 36, true), lsbFirst: bits === 1, blockSize};
    } else if (id === 'data') {
      if (data) fail('Tệp DSF chứa nhiều vùng dữ liệu âm thanh.');
      data = {dataOffset: offset + 12, dataSize: length - 12};
    }
    if (format && data) break;
    offset += length;
  }
  if (!format || !data) fail('Không tìm thấy chunk fmt/data trong tệp DSF.');
  const metadata = finishMetadata({kind: 'dsf', ...format, ...data});
  const bytesPerChannel = Math.ceil(metadata.sampleCount / 8);
  const required = Math.ceil(bytesPerChannel / metadata.blockSize) * metadata.blockSize * metadata.channels;
  if (required > metadata.dataSize) fail('Dữ liệu DSF không đủ cho số mẫu đã khai báo.');
  return metadata;
}
async function inspectDFF(source, initial) {
  const size = sourceSize(source);
  const declaredEnd = uint64(view(initial), 4, false) + 12;
  if (declaredEnd > size || declaredEnd < 16 || text(initial, 12, 4) !== 'DSD ') fail('Header DSDIFF không hợp lệ.');
  let offset = 16, sampleRate = 0, channels = 0, compression = null, data = null;
  async function property(start, end) {
    if (end - start < 4 || text(await read(source, start, 4), 0, 4) !== 'SND ') return;
    let cursor = start + 4;
    for (let chunks = 0; cursor + 12 <= end && chunks < 10000; chunks++) {
      const head = await read(source, cursor, 12), id = text(head, 0, 4);
      const length = uint64(view(head), 4, false), body = cursor + 12;
      if (!validRange(body, length, end)) fail('Thuộc tính DSDIFF bị hỏng.');
      if (id === 'FS  ') {
        if (length < 4) fail('Thiếu tần số DSDIFF.');
        sampleRate = view(await read(source, body, 4)).getUint32(0, false);
      } else if (id === 'CHNL') {
        if (length < 2) fail('Thiếu số kênh DSDIFF.');
        channels = view(await read(source, body, 2)).getUint16(0, false);
        if (length < 2 + channels * 4) fail('Danh sách kênh DSDIFF bị cắt ngắn.');
      } else if (id === 'CMPR') {
        if (length < 4) fail('Thiếu kiểu nén DSDIFF.');
        compression = text(await read(source, body, 4), 0, 4);
      }
      cursor = body + length + (length % 2);
    }
    if (cursor !== end) fail('Chunk thuộc tính DSDIFF không thẳng hàng.');
  }
  for (let chunks = 0; offset + 12 <= declaredEnd && chunks < 10000; chunks++) {
    const head = await read(source, offset, 12), id = text(head, 0, 4);
    const length = uint64(view(head), 4, false), body = offset + 12;
    if (!validRange(body, length, declaredEnd)) fail('Chunk DSDIFF bị hỏng hoặc vượt phạm vi tệp.');
    if (id === 'PROP') await property(body, body + length);
    if (id === 'DST ') fail('DFF nén DST chưa được hỗ trợ; hãy dùng DSF hoặc DFF DSD không nén.', 'UNSUPPORTED_DST');
    if (id === 'DSD ') {
      if (data) fail('Tệp DSDIFF chứa nhiều vùng dữ liệu âm thanh.');
      data = {dataOffset: body, dataSize: length};
    }
    offset = body + length + (length % 2);
  }
  if (compression === 'DST ') fail('DFF nén DST chưa được hỗ trợ; hãy dùng DSF hoặc DFF DSD không nén.', 'UNSUPPORTED_DST');
  if (compression && compression !== 'DSD ') fail('Kiểu nén DSDIFF chưa được hỗ trợ.', 'UNSUPPORTED_DFF_COMPRESSION');
  if (!data || !sampleRate || !channels) fail('Thiếu thuộc tính hoặc dữ liệu âm thanh DSDIFF.');
  if (data.dataSize % channels !== 0) fail('Dữ liệu kênh DSDIFF không thẳng hàng.');
  return finishMetadata({kind: 'dff', channels, sampleRate, sampleCount: data.dataSize / channels * 8,
    lsbFirst: false, ...data});
}

export async function inspectDSD(source) {
  sourceSize(source);
  const initial = await read(source, 0, 28), magic = text(initial, 0, 4);
  if (magic === 'DSD ') return inspectDSF(source, initial);
  if (magic === 'FRM8') return inspectDFF(source, initial);
  fail('Tệp này không phải DSF hoặc DSDIFF hợp lệ.', 'NOT_DSD');
}
function bessel0(x) {
  let sum = 1, term = 1;
  for (let k = 1; k < 40; k++) { term *= x * x / (4 * k * k); sum += term; if (term < sum * 1e-14) break; }
  return sum;
}
function filterTable(ratio, lsbFirst) {
  const key = `${ratio}:${lsbFirst}`;
  if (tables.has(key)) return tables.get(key);
  // 32 output periods, Kaiser beta 8.6: a real reconstruction/anti-alias filter.
  // Byte lookup tables evaluate all eight 1-bit multiply/add operations at once.
  const taps = ratio * 32, groups = taps / 8, coefficients = new Float64Array(taps);
  const beta = 8.6, denominator = bessel0(beta), cutoff = .32 / ratio;
  let sum = 0;
  for (let n = 0; n < taps; n++) {
    const position = n - (taps - 1) / 2;
    const sinc = position === 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * position) / (Math.PI * position);
    const normalized = 2 * n / (taps - 1) - 1;
    const value = sinc * bessel0(beta * Math.sqrt(Math.max(0, 1 - normalized * normalized))) / denominator;
    coefficients[n] = value; sum += value;
  }
  const lookup = new Float32Array(groups * 256);
  for (let group = 0; group < groups; group++) {
    for (let byte = 0; byte < 256; byte++) {
      let value = 0;
      for (let bit = 0; bit < 8; bit++) {
        const set = (byte >> (lsbFirst ? bit : 7 - bit)) & 1;
        value += (set ? 1 : -1) * coefficients[group * 8 + bit] / sum;
      }
      lookup[group * 256 + byte] = value;
    }
  }
  const result = {lookup, groups, halfBytes: taps / 16, bytesPerFrame: ratio / 8};
  tables.set(key, result); return result;
}

/** Direct reader used inside the dedicated worker and by decoder verification. */
export class DSDPCMReader {
  constructor(source, metadata) { this.source = source; this.metadata = metadata; }
  static async open(source) { return new DSDPCMReader(source, await inspectDSD(source)); }
  async decode(startFrame, requestedFrames) {
    const meta = this.metadata;
    if (!Number.isSafeInteger(startFrame) || startFrame < 0 || !Number.isSafeInteger(requestedFrames) || requestedFrames < 1 || requestedFrames > MAX_REQUEST_FRAMES)
      fail('Vị trí hoặc kích thước khối giải mã DSD không hợp lệ.');
    const frameCount = Math.min(requestedFrames, Math.max(0, meta.totalPCMFrames - startFrame));
    const output = Array.from({length: meta.channels}, () => new Float32Array(frameCount));
    if (!frameCount) return {channels: output, sampleRate: meta.pcmSampleRate, startFrame, frameCount: 0, eof: true};
    const filter = filterTable(meta.decimation, meta.lsbFirst);
    const startByte = startFrame * filter.bytesPerFrame - filter.halfBytes;
    const endByte = (startFrame + frameCount - 1) * filter.bytesPerFrame - filter.halfBytes + filter.groups;
    const channelBytes = Math.ceil(meta.sampleCount / 8);
    const readStart = Math.max(0, startByte), readEnd = Math.min(channelBytes, endByte);
    const raw = Array.from({length: meta.channels}, () => new Uint8Array(endByte - startByte).fill(SILENCE_BYTE));
    const destination = readStart - startByte;
    if (readEnd > readStart) {
      if (meta.kind === 'dsf') {
        const firstBlock = Math.floor(readStart / meta.blockSize), lastBlock = Math.floor((readEnd - 1) / meta.blockSize);
        const physicalOffset = firstBlock * meta.blockSize * meta.channels;
        const physicalLength = (lastBlock - firstBlock + 1) * meta.blockSize * meta.channels;
        const bytes = await read(this.source, meta.dataOffset + physicalOffset, physicalLength);
        for (let channel = 0; channel < meta.channels; channel++) {
          for (let block = firstBlock; block <= lastBlock; block++) {
            const begin = Math.max(readStart, block * meta.blockSize), end = Math.min(readEnd, (block + 1) * meta.blockSize);
            const from = (block - firstBlock) * meta.blockSize * meta.channels + channel * meta.blockSize + begin - block * meta.blockSize;
            raw[channel].set(bytes.subarray(from, from + end - begin), destination + begin - readStart);
          }
        }
      } else {
        const bytes = await read(this.source, meta.dataOffset + readStart * meta.channels, (readEnd - readStart) * meta.channels);
        for (let channel = 0; channel < meta.channels; channel++)
          for (let n = 0; n < readEnd - readStart; n++) raw[channel][destination + n] = bytes[n * meta.channels + channel];
      }
    }
    // The final partial source byte may contain DSF block padding; replace only
    // out-of-range bits with a balanced DSD silence pattern before filtering.
    const remainder = meta.sampleCount % 8;
    if (remainder && readEnd === channelBytes) {
      const index = channelBytes - 1 - startByte;
      const mask = meta.lsbFirst ? (1 << remainder) - 1 : (0xff << (8 - remainder)) & 0xff;
      for (const channel of raw) channel[index] = (channel[index] & mask) | (SILENCE_BYTE & ~mask);
    }
    const {lookup, groups, bytesPerFrame} = filter;
    for (let channel = 0; channel < meta.channels; channel++) {
      const input = raw[channel], pcm = output[channel];
      for (let frame = 0, base = 0; frame < frameCount; frame++, base += bytesPerFrame) {
        let value = 0;
        for (let group = 0; group < groups; group++) value += lookup[group * 256 + input[base + group]];
        pcm[frame] = value;
      }
    }
    return {channels: output, sampleRate: meta.pcmSampleRate, startFrame, frameCount,
      eof: startFrame + frameCount >= meta.totalPCMFrames};
  }
}

/** Worker client. Source is a Blob/File or {url,size,name} with ranged reads. */
export class DSDStreamDecoder {
  constructor(source) { this.source = source; this.worker = null; this.pending = new Map(); this.sequence = 0; this.closed = false; this.initialization = null; }
  init(source = this.source) {
    if (this.closed) return Promise.reject(new Error('Bộ giải mã DSD đã đóng.'));
    if (this.initialization) return this.initialization;
    this.source = source;
    try {
      this.worker = new Worker(new URL('./dsd-worker.js', import.meta.url), {type: 'module', name: 'walkman-dsd-decoder'});
      this.worker.onmessage = event => {
        const {requestId, result, error} = event.data || {}, pending = this.pending.get(requestId);
        if (!pending) return;
        this.pending.delete(requestId);
        if (error) { const problem = new Error(error.message); problem.code = error.code; pending.reject(problem); }
        else pending.resolve(result);
      };
      this.worker.onerror = () => {
        const problem = new Error('Không khởi động được bộ giải mã DSD ngoại tuyến.');
        for (const pending of this.pending.values()) pending.reject(problem);
        this.pending.clear();
      };
      this.initialization = this.request('init', {source}).then(metadata => { this.metadata = metadata; return metadata; });
    } catch (error) { this.initialization = Promise.reject(error); }
    return this.initialization;
  }
  request(type, details) {
    if (this.closed || !this.worker) return Promise.reject(new Error('Bộ giải mã DSD chưa sẵn sàng.'));
    const requestId = ++this.sequence;
    return new Promise((resolve, reject) => {
      this.pending.set(requestId, {resolve, reject});
      try { this.worker.postMessage({type, requestId, ...details}); }
      catch (error) { this.pending.delete(requestId); reject(error); }
    });
  }
  async decode(startFrame, frameCount) { await this.init(); return this.request('decode', {startFrame, frameCount}); }
  close() {
    this.closed = true;
    this.worker?.terminate(); this.worker = null;
    const error = new DOMException('Đã đóng bộ giải mã DSD.', 'AbortError');
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
  }
}
