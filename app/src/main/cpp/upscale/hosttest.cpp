// host-only check: model loads, 4x output of a 64x48 gradient.
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "stable-diffusion.h"
int main(int argc, char** argv) {
    upscaler_ctx_t* c = new_upscaler_ctx(argv[1], false, 2, 128, nullptr, nullptr);
    if (!c) { printf("LOAD FAIL\n"); return 1; }
    int f = get_upscale_factor(c);
    int w = 64, h = 48;
    std::vector<uint8_t> px(w * h * 3);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { uint8_t* p = &px[(y * w + x) * 3]; p[0] = x * 4; p[1] = y * 5; p[2] = ((x / 8 + y / 8) & 1) ? 230 : 20; }
    sd_image_t in{(uint32_t)w, (uint32_t)h, 3, px.data()};
    sd_image_generation_result_t* r = nullptr;
    bool ok = upscale(c, in, f, &r);
    if (!ok || !r || r->count < 1) { printf("UPSCALE FAIL\n"); return 2; }
    sd_image_t o = r->items[0].image;
    long sum = 0; for (size_t i = 0; i < (size_t)o.width * o.height * o.channel; i++) sum += o.data[i];
    printf("OK factor=%d out=%ux%ux%u mean=%.1f\n", f, o.width, o.height, o.channel, (double)sum / ((size_t)o.width * o.height * o.channel));
    FILE* fp = fopen("/tmp/up.ppm", "wb"); fprintf(fp, "P6 %u %u 255\n", o.width, o.height); fwrite(o.data, 1, (size_t)o.width * o.height * 3, fp); fclose(fp);
    free_sd_image_generation_result(r); free_upscaler_ctx(c);
    return 0;
}
