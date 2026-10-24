#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// The GTA V switch look. Params pixel 0: grading strength, flash, unused, vignette. Pixel 2: character colour.

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float noise(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec3 tint = texelFetch(ParamsSampler, ivec2(2, 0), 0).rgb;
    float strength = params.r;
    float flash = params.g;
    float vignette = params.a;

    vec3 color = texture(InSampler, texCoord).rgb;

    // Nearly black and white, a breath of the character colour, punchy mids, lifted blacks: the washed out sky cam.
    float luma = dot(color, LUMA);
    vec3 graded = mix(vec3(luma), color, 0.22);
    graded *= mix(vec3(1.0), tint * 1.3, 0.25);
    graded = (graded - 0.45) * 1.1 + 0.5;
    graded = graded * 0.88 + 0.08;
    color = mix(color, clamp(graded, 0.0, 1.0), strength);

    vec2 centered = (texCoord - vec2(0.5)) * vec2(ScreenSize.x / max(ScreenSize.y, 1.0), 1.0);

    // The pulse at every jump: an overexposed flash washed with the character colour.
    vec3 flashColor = mix(vec3(1.0), tint, 0.4);
    color = mix(color, color * 1.8 + 0.25, flash * 0.8);
    color = mix(color, flashColor, flash * 0.55);

    float vig = 1.0 - smoothstep(0.35, 1.05, length(centered));
    color *= mix(1.0, 0.55 + 0.45 * vig, vignette);

    float grain = noise(gl_FragCoord.xy + fract(GameTime * 2400.0) * 517.0) - 0.5;
    color += grain * 0.035 * strength;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
