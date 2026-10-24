#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// Motion blur and clouds of the switch camera. Looking down from the sky, sky in the picture means world that isn't
// drawn (not loaded yet or any more, and no Distant Horizons terrain there either): clouds go there, frayed into the
// ground around it, and they are laid under every blur sample so they streak with the picture. Params pixel 1:
// radial blur, directional blur, blur direction; pixel 2: character colour, cloud amount; pixel 3: cloud offset,
// 16 bits per axis.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D FarDepthSampler;
uniform sampler2D ParamsSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const int SAMPLES = 20;
// The cloud pattern repeats every CLOUD_PERIOD cells, so the offset can wrap around without a seam.
const float CLOUD_PERIOD = 8.0;
const float CLOUD_SCALE = 2.5;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float valueNoise(vec2 p, float period) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(mod(cell, period));
    float b = hash(mod(cell + vec2(1.0, 0.0), period));
    float c = hash(mod(cell + vec2(0.0, 1.0), period));
    float d = hash(mod(cell + vec2(1.0, 1.0), period));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float clouds(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    float period = CLOUD_PERIOD;
    for (int octave = 0; octave < 4; octave++) {
        value += amplitude * valueNoise(p, period);
        p = p * 2.0 + vec2(5.0, 11.0);
        period *= 2.0;
        amplitude *= 0.5;
    }
    return value / 0.9375;
}

bool isClear(float depth) {
    // The clear value: 0 with reversed depth, 1 without.
    return depth < 1.0e-7 || depth > 0.9999999;
}

bool isSky(vec2 uv) {
    return isClear(texture(DepthSampler, uv).r) && isClear(texture(FarDepthSampler, uv).r);
}

// How much of the neighbourhood is empty sky, soft so that the clouds overlap the edge of the drawn ground.
float voidMask(vec2 uv, float aspect) {
    float sum = isSky(uv) ? 2.0 : 0.0;
    vec2 radius = vec2(0.08 / aspect, 0.08);
    for (int i = 0; i < 8; i++) {
        float angle = float(i) * 0.7853982;
        sum += isSky(uv + vec2(cos(angle), sin(angle)) * radius) ? 1.0 : 0.0;
    }
    return sum / 10.0;
}

vec3 clouded(vec2 uv, float voidAmount, vec2 cloudOffset, vec3 tint, float aspect) {
    vec3 color = texture(InSampler, uv).rgb;
    if (voidAmount < 0.004) {
        return color;
    }
    vec2 p = (uv - vec2(0.5)) * vec2(aspect, 1.0) * CLOUD_SCALE + cloudOffset * CLOUD_PERIOD;
    float density = clouds(p);
    float cover = smoothstep(0.25, 0.6, voidAmount + (density - 0.5) * 0.5);
    vec3 cloud = mix(vec3(0.66, 0.71, 0.77), vec3(0.96, 0.97, 0.99), smoothstep(0.25, 0.9, density));
    cloud = mix(cloud, cloud * tint * 1.25, 0.12);
    return mix(color, cloud, cover);
}

void main() {
    vec4 motion = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 colourAndVoid = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec3 tint = colourAndVoid.rgb;
    vec4 offsetBytes = floor(texelFetch(ParamsSampler, ivec2(3, 0), 0) * 255.0 + 0.5);
    vec2 cloudOffset = vec2(offsetBytes.r * 256.0 + offsetBytes.g, offsetBytes.b * 256.0 + offsetBytes.a) / 65536.0;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    float voidAmount = colourAndVoid.a > 0.004 ? voidMask(texCoord, aspect) * colourAndVoid.a : 0.0;

    float radial = motion.r;
    float directional = motion.g;
    vec2 direction = motion.ba * 2.0 - 1.0;

    if (radial < 0.004 && directional < 0.004) {
        fragColor = vec4(clouded(texCoord, voidAmount, cloudOffset, tint, aspect), 1.0);
        return;
    }

    // Zoom streaks toward the centre while the camera shoots up or down, a smear across the screen while it flies over.
    vec2 radialStep = (vec2(0.5) - texCoord) * radial * 0.2;
    vec2 directionalStep = direction * directional * 0.09;
    float jitter = hash(gl_FragCoord.xy) / float(SAMPLES);

    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float t = float(i) / float(SAMPLES) + jitter;
        float weight = 1.0 - 0.6 * t;
        vec2 uv = texCoord + radialStep * t + directionalStep * (t - 0.5);
        sum += clouded(uv, voidAmount, cloudOffset, tint, aspect) * weight;
        total += weight;
    }
    vec3 color = sum / total;

    // A touch of lens fringing on the fastest frames.
    vec2 fringe = (texCoord - vec2(0.5)) * radial * 0.012;
    float fringeAmount = radial * 0.5 * (1.0 - voidAmount);
    color.r = mix(color.r, texture(InSampler, texCoord + fringe).r, fringeAmount);
    color.b = mix(color.b, texture(InSampler, texCoord - fringe).b, fringeAmount);

    fragColor = vec4(color, 1.0);
}
