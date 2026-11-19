const stride = 8;
const gpuStride = 12;
const meshNames = ["mannequin", "head", "chest", "legs", "feet"];
const skinMeshes = ["mannequin_slim", "skin_overlay", "skin_overlay_slim"];
const armorSlots = ["legs", "feet", "chest", "head"];

const vertexSource = `
  attribute vec3 position;
  attribute vec2 uv;
  attribute vec3 normal;
  attribute vec4 uvBounds;
  uniform vec3 center;
  uniform vec2 extent;
  uniform vec2 turn;
  uniform vec2 tilt;
  varying vec2 textureUV;
  varying vec4 textureBounds;
  varying float shading;

  vec3 orbit(vec3 v) {
    vec3 turned = vec3(turn.x * v.x + turn.y * v.z, v.y, -turn.y * v.x + turn.x * v.z);
    return vec3(turned.x, tilt.x * turned.y + tilt.y * turned.z, -tilt.y * turned.y + tilt.x * turned.z);
  }

  void main() {
    vec3 orientation = vec3(1.0, -1.0, 1.0);
    vec3 view = orbit((position - center) * orientation);
    vec3 litNormal = normalize(orbit(normal * orientation));
    shading = 0.71 + 0.22 * max(dot(litNormal, normalize(vec3(-0.4, 0.8, -0.7))), 0.0)
                   + 0.10 * max(dot(litNormal, normalize(vec3(0.6, 0.4, 0.7))), 0.0);
    gl_Position = vec4(view.xy / extent, view.z / 4.0, 1.0);
    textureUV = uv;
    textureBounds = uvBounds;
  }
`;

const fragmentSource = `
  precision highp float;
  uniform sampler2D atlas;
  uniform bool textured;
  uniform vec2 halfTexel;
  varying vec2 textureUV;
  varying vec4 textureBounds;
  varying float shading;

  void main() {
    vec2 inset = min(halfTexel, (textureBounds.zw - textureBounds.xy) * 0.5);
    vec2 sampleUV = clamp(textureUV, textureBounds.xy + inset, textureBounds.zw - inset);
    vec4 color = textured ? texture2D(atlas, sampleUV) : vec4(0.57, 0.65, 0.60, 1.0);
    if (color.a < 0.1) discard;
    gl_FragColor = vec4(color.rgb * shading, 1.0);
  }
`;

export function createArmorScene(canvas, geometry) {
  if (!hasMeshes(geometry)) return null;
  const gl = canvas.getContext("webgl", { alpha: true, antialias: true, preserveDrawingBuffer: true });
  if (!gl) return null;
  const program = makeProgram(gl);
  gl.useProgram(program);
  gl.viewport(0, 0, canvas.width, canvas.height);
  gl.clearColor(0, 0, 0, 0);
  gl.enable(gl.DEPTH_TEST);
  gl.depthFunc(gl.LEQUAL);

  const attributes = [["position", 3, 0], ["uv", 2, 3], ["normal", 3, 5], ["uvBounds", 4, 8]].map(
    ([name, size, offset]) => [gl.getAttribLocation(program, name), size, offset]);
  const uniform = name => gl.getUniformLocation(program, name);
  const turn = uniform("turn");
  const tilt = uniform("tilt");
  const textured = uniform("textured");
  const halfTexel = uniform("halfTexel");
  const wardrobe = Object.fromEntries([...meshNames, ...skinMeshes].filter(name => validMesh(geometry.meshes[name]))
    .map(name => [name, meshBuffer(gl, geometry.meshes[name])]));
  const { center, height } = bounds(geometry.meshes.mannequin);
  const extent = height * 0.58;
  gl.uniform3fv(uniform("center"), center);
  gl.uniform2f(uniform("extent"), extent * canvas.width / canvas.height, extent);

  gl.activeTexture(gl.TEXTURE0);
  gl.uniform1i(uniform("atlas"), 0);
  const textures = new Map();
  const equipped = new Set();
  let skinModel;

  function upload(name, image) {
    let texture = textures.get(name);
    if (!texture) {
      texture = { handle: gl.createTexture() };
      textures.set(name, texture);
      gl.bindTexture(gl.TEXTURE_2D, texture.handle);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    }
    gl.bindTexture(gl.TEXTURE_2D, texture.handle);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
    texture.u = 0.5 / image.width;
    texture.v = 0.5 / image.height;
  }

  function drawMesh(mesh, name) {
    const texture = textures.get(name);
    if (texture) {
      gl.bindTexture(gl.TEXTURE_2D, texture.handle);
      gl.uniform2f(halfTexel, texture.u, texture.v);
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.vertices);
    for (const [index, size, offset] of attributes) {
      gl.enableVertexAttribArray(index);
      gl.vertexAttribPointer(index, size, gl.FLOAT, false, gpuStride * 4, offset * 4);
    }
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.triangles);
    gl.uniform1i(textured, texture ? 1 : 0);
    gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_SHORT, 0);
  }

  return {
    equip(layers) {
      equipped.clear();
      for (const [slot, atlas] of layers) {
        upload(slot, atlas);
        equipped.add(slot);
      }
    },
    setSkin(skin) {
      skinModel = skin ? (skin.slim && wardrobe.mannequin_slim ? "slim" : "classic") : undefined;
      if (skin) upload("skin", skin.image);
    },
    draw(angle, elevation) {
      const radians = angle * Math.PI / 180;
      const pitch = elevation * Math.PI / 180;
      gl.uniform2f(turn, Math.cos(radians), Math.sin(radians));
      gl.uniform2f(tilt, Math.cos(pitch), Math.sin(pitch));
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      const slim = skinModel === "slim";
      drawMesh(slim ? wardrobe.mannequin_slim : wardrobe.mannequin, skinModel ? "skin" : undefined);
      const overlay = slim ? wardrobe.skin_overlay_slim : wardrobe.skin_overlay;
      if (skinModel && overlay) drawMesh(overlay, "skin");
      for (const slot of armorSlots) {
        if (equipped.has(slot)) drawMesh(wardrobe[slot], slot);
      }
    },
  };
}

function hasMeshes(geometry) {
  return geometry?.version === 1 && meshNames.every(name => validMesh(geometry.meshes?.[name]));
}

function validMesh(data) {
  return Array.isArray(data) && data.length > 0 && data.length <= 16384
    && data.length % (stride * 4) === 0 && data.every(Number.isFinite);
}

function meshBuffer(gl, data) {
  const packed = new Float32Array(data.length / stride * gpuStride);
  const indices = [];
  for (let first = 0; first < data.length / stride; first += 4) {
    indices.push(first, first + 1, first + 2, first, first + 2, first + 3);
    // Multisample interpolation can fall outside a face even when its vertices are inside the UV rectangle.
    const u = [0, 1, 2, 3].map(offset => data[(first + offset) * stride + 3]);
    const v = [0, 1, 2, 3].map(offset => data[(first + offset) * stride + 4]);
    const limits = [Math.min(...u), Math.min(...v), Math.max(...u), Math.max(...v)];
    for (let vertex = first; vertex < first + 4; vertex++) {
      packed.set(data.slice(vertex * stride, (vertex + 1) * stride), vertex * gpuStride);
      packed.set(limits, vertex * gpuStride + stride);
    }
  }
  const vertices = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, vertices);
  gl.bufferData(gl.ARRAY_BUFFER, packed, gl.STATIC_DRAW);
  const triangles = gl.createBuffer();
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, triangles);
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(indices), gl.STATIC_DRAW);
  return { vertices, triangles, count: indices.length };
}

function bounds(vertices) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (let offset = 0; offset < vertices.length; offset += stride) {
    for (let axis = 0; axis < 3; axis++) {
      min[axis] = Math.min(min[axis], vertices[offset + axis]);
      max[axis] = Math.max(max[axis], vertices[offset + axis]);
    }
  }
  return { center: min.map((value, axis) => (value + max[axis]) / 2), height: max[1] - min[1] };
}

function makeProgram(gl) {
  const program = gl.createProgram();
  for (const [type, code] of [[gl.VERTEX_SHADER, vertexSource], [gl.FRAGMENT_SHADER, fragmentSource]]) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, code);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const message = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      gl.deleteProgram(program);
      throw new Error(message);
    }
    gl.attachShader(program, shader);
    gl.deleteShader(shader);
  }
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const message = gl.getProgramInfoLog(program);
    gl.deleteProgram(program);
    throw new Error(message);
  }
  return program;
}
