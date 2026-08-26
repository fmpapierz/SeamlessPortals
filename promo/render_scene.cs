using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

public class VWorld
{
    public int SX, SY, SZ;
    public byte[] B, Sky, Blk;
    public VWorld(int sx, int sy, int sz)
    {
        SX = sx; SY = sy; SZ = sz;
        int n = sx * sy * sz;
        B = new byte[n]; Sky = new byte[n]; Blk = new byte[n];
    }
    public bool In(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ; }
    public int I(int x, int y, int z) { return (y * SZ + z) * SX + x; }
    public byte Get(int x, int y, int z) { if (!In(x, y, z)) return 0; return B[(y * SZ + z) * SX + x]; }
    public void Set(int x, int y, int z, byte v) { if (In(x, y, z)) B[(y * SZ + z) * SX + x] = v; }
    public byte SkyL(int x, int y, int z) { if (!In(x, y, z)) return (byte)(y >= SY ? 15 : 0); return Sky[(y * SZ + z) * SX + x]; }
    public byte BlkL(int x, int y, int z) { if (!In(x, y, z)) return 0; return Blk[(y * SZ + z) * SX + x]; }
}

public static class PortalScene
{
    // ---------------- config ----------------
    const int W = 1920, H = 1080;
    const double FOV = 55.0;

    const int OX = 320, OY = 48, OZ = 320;
    const int NX = 128, NY = 56, NZ = 128;

    // solid blocks
    const byte AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, LOG = 4, LEAVES = 5,
               OBSID = 6, NRACK = 7, LAVA = 8, GLOW = 9, GRAVEL = 10, PORTAL = 11,
               BLOG = 12, BLEAVES = 13, COBBLE = 14;
    // cross-quad plants (id >= 20)
    const byte SGRASS = 20, FERN = 21, POPPY = 22, DANDE = 23, CORNF = 24,
               OXEYE = 25, AZURE = 26, TGRASS_B = 27, TGRASS_T = 28;

    const int T_GTOP = 0, T_GSIDE = 1, T_DIRT = 2, T_STONE = 3, T_LOGS = 4, T_LOGT = 5,
              T_LEAF = 6, T_OBS = 7, T_NRACK = 8, T_LAVA = 9, T_GLOW = 10, T_GRAVEL = 11,
              T_BLOGS = 12, T_BLOGT = 13, T_BLEAF = 14, T_SGRASS = 15, T_FERN = 16,
              T_POPPY = 17, T_DANDE = 18, T_CORNF = 19, T_OXEYE = 20, T_AZURE = 21,
              T_TGB = 22, T_TGT = 23, T_COBBLE = 24;
    const int NTEX = 25;

    static int[] topTex = new int[32], sideTex = new int[32], botTex = new int[32];

    // portal geometry (overworld)
    const int GY = 20;
    const double PZ = 170.5;
    const double AX0 = 158.0, AX1 = 162.0, AY0 = 21.0, AY1 = 26.0;
    const double DX = -96.0, DY = 3.0, DZ = -106.0;

    static VWorld ow, nw;
    static int[][][] tex;
    static Random rng = new Random(20260719);

    static double[] camP = new double[3], camF = new double[3], camR = new double[3], camU = new double[3];
    static double tanHalf, pixAng;

    static int[] fb = new int[W * H];
    static double[] depth = new double[W * H];
    static bool[] thru = new bool[W * H];

    static int Cl(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    static double Sat(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static bool IsCross(byte b) { return b >= 20; }
    static bool Solid(byte b) { return b != AIR && b != PORTAL && b < 20; }
    static bool LightBlocking(byte b) { return Solid(b) && b != LEAVES && b != BLEAVES; }
    static int LightOpacity(byte b) { return (b == LEAVES || b == BLEAVES) ? 1 : 0; }
    static bool Emissive(byte b) { return b == LAVA || b == GLOW; }
    static byte Emission(byte b) { if (b == LAVA || b == GLOW) return 15; if (b == PORTAL) return 11; return 0; }

    // ---------------- noise ----------------
    static double Hash2(int x, int z, int seed)
    {
        int h = x * 374761393 + z * 668265263 + seed * 1442695041;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return (h & 0x7fffffff) / 2147483647.0;
    }
    static double Noise(double x, double z, int seed)
    {
        int xi = (int)Math.Floor(x), zi = (int)Math.Floor(z);
        double fx = x - xi, fz = z - zi;
        double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
        double a = Hash2(xi, zi, seed), b = Hash2(xi + 1, zi, seed);
        double c = Hash2(xi, zi + 1, seed), d = Hash2(xi + 1, zi + 1, seed);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    // ---------------- textures (ARGB) ----------------
    static int C4(int a, int r, int g, int b) { return (Cl(a) << 24) | (Cl(r) << 16) | (Cl(g) << 8) | Cl(b); }
    static int RGB(int r, int g, int b) { return C4(255, r, g, b); }

    static int[] Fill(int r, int g, int b)
    {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) t[i] = RGB(r, g, b);
        return t;
    }
    static int[] Clear() { return new int[256]; }
    static void Px(int[] t, int x, int y, int r, int g, int b) { if (x >= 0 && y >= 0 && x < 16 && y < 16) t[y * 16 + x] = RGB(r, g, b); }

    static int[] TexGrassTop()
    {
        int[] t = Fill(112, 158, 66);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.30) t[i] = RGB(98, 142, 56);
            else if (r < 0.50) t[i] = RGB(124, 172, 76);
            else if (r < 0.58) t[i] = RGB(86, 128, 48);
        }
        return t;
    }
    static int[] TexDirt()
    {
        int[] t = Fill(134, 98, 68);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.26) t[i] = RGB(148, 110, 78);
            else if (r < 0.48) t[i] = RGB(118, 84, 58);
            else if (r < 0.56) t[i] = RGB(160, 122, 88);
            else if (r < 0.62) t[i] = RGB(104, 72, 50);
        }
        return t;
    }
    static int[] TexGrassSide()
    {
        int[] t = TexDirt();
        for (int x = 0; x < 16; x++)
        {
            int d = 3 + (rng.NextDouble() < 0.5 ? 1 : 0);
            for (int y = 0; y < d; y++)
                if (y < 3 || rng.NextDouble() < 0.55)
                {
                    double r = rng.NextDouble();
                    if (r < 0.4) Px(t, x, y, 112, 158, 66);
                    else if (r < 0.75) Px(t, x, y, 98, 142, 56);
                    else Px(t, x, y, 124, 172, 76);
                }
        }
        return t;
    }
    static int[] TexStone()
    {
        int[] t = Fill(128, 128, 128);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.3) t[i] = RGB(118, 118, 118);
            else if (r < 0.5) t[i] = RGB(138, 138, 138);
            else if (r < 0.56) t[i] = RGB(106, 106, 106);
        }
        return t;
    }
    static int[] TexCobble()
    {
        int[] t = Fill(122, 122, 122);
        for (int i = 0; i < 8; i++)
        {
            int cx = rng.Next(16), cy = rng.Next(16), s = 2 + rng.Next(3);
            int v = 96 + rng.Next(60);
            for (int dy = 0; dy < s; dy++) for (int dx = 0; dx < s; dx++)
                    Px(t, (cx + dx) % 16, (cy + dy) % 16, v, v, v);
        }
        for (int i = 0; i < 60; i++) { int v = 84 + rng.Next(70); Px(t, rng.Next(16), rng.Next(16), v, v, v); }
        return t;
    }
    static int[] TexGravel()
    {
        int[] t = Fill(130, 124, 120);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.3) t[i] = RGB(112, 106, 104);
            else if (r < 0.55) t[i] = RGB(146, 140, 136);
            else if (r < 0.62) t[i] = RGB(94, 90, 88);
        }
        return t;
    }
    static int[] TexLogSide(int br, int bg, int bb, bool birch)
    {
        int[] t = Fill(br, bg, bb);
        for (int x = 0; x < 16; x++)
        {
            int shade = rng.Next(0, 3);
            for (int y = 0; y < 16; y++)
            {
                int b = shade == 0 ? 0 : (shade == 1 ? 11 : -11);
                if (rng.NextDouble() < 0.25) b += rng.Next(-7, 8);
                Px(t, x, y, br + b, bg + b, bb + (int)(b * 0.6));
            }
        }
        if (birch)
        {
            for (int i = 0; i < 7; i++)
            {
                int x = rng.Next(15), y = rng.Next(14), w = 1 + rng.Next(3);
                for (int k = 0; k < w; k++) Px(t, x + k, y, 62, 58, 54);
            }
        }
        else
        {
            for (int i = 0; i < 5; i++)
            {
                int x = rng.Next(16), y = rng.Next(12);
                for (int k = 0; k < 3; k++) Px(t, x, y + k, (int)(br * 0.68), (int)(bg * 0.66), (int)(bb * 0.62));
            }
        }
        return t;
    }
    static int[] TexLogTop(int br, int bg, int bb)
    {
        int[] t = Fill(br, bg, bb);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            {
                double d = Math.Sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5));
                int ring = ((int)(d * 1.4)) % 2;
                int b = ring == 0 ? 0 : -18;
                if (d > 7.0) b = -46;
                Px(t, x, y, br + b, bg + b, bb + (int)(b * 0.7));
            }
        return t;
    }
    // fancy leaves: cutout holes so you can see through the canopy
    static int[] TexLeaves(int lr, int lg, int lb, int seed)
    {
        int[] t = new int[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            {
                double n = Noise(x * 0.55 + seed, y * 0.55 + seed, 400 + seed);
                if (n < 0.20) { t[y * 16 + x] = 0; continue; }   // hole
                int v;
                double r = rng.NextDouble();
                if (r < 0.30) v = -22; else if (r < 0.56) v = 16; else if (r < 0.66) v = -40; else v = 0;
                t[y * 16 + x] = RGB(lr + v, lg + v, lb + (int)(v * 0.7));
            }
        for (int i = 0; i < 8; i++) t[rng.Next(256)] = 0;
        return t;
    }
    static int[] TexObsidian()
    {
        int[] t = Fill(16, 12, 26);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.34) t[i] = RGB(23, 18, 37);
            else if (r < 0.46) t[i] = RGB(10, 8, 17);
        }
        for (int i = 0; i < 5; i++)
        {
            int cx = rng.Next(1, 15), cy = rng.Next(1, 15);
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
                    if (rng.NextDouble() < 0.7) Px(t, cx + dx, cy + dy, 34, 26, 54);
            Px(t, cx, cy, 44, 34, 70);
        }
        for (int i = 0; i < 4; i++) Px(t, rng.Next(16), rng.Next(16), 56, 44, 88);
        return t;
    }
    static int[] TexNetherrack()
    {
        int[] t = Fill(106, 44, 44);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.26) t[i] = RGB(122, 54, 50);
            else if (r < 0.48) t[i] = RGB(88, 32, 32);
            else if (r < 0.56) t[i] = RGB(134, 62, 56);
        }
        for (int v = 0; v < 5; v++)
        {
            int x = rng.Next(16);
            for (int y = 0; y < 16; y++) { x = (x + rng.Next(-1, 2) + 16) % 16; Px(t, x, y, 74, 26, 26); }
        }
        return t;
    }
    static int[] TexLava()
    {
        int[] t = Fill(228, 108, 22);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            {
                double n = Noise(x * 0.35, y * 0.35, 77);
                if (n < 0.36) Px(t, x, y, 196, 76, 14);
                else if (n < 0.58) Px(t, x, y, 232, 116, 24);
                else if (n < 0.76) Px(t, x, y, 248, 156, 40);
                else Px(t, x, y, 255, 202, 84);
            }
        return t;
    }
    static int[] TexGlowstone()
    {
        int[] t = Fill(214, 160, 66);
        for (int i = 0; i < 256; i++)
        {
            double r = rng.NextDouble();
            if (r < 0.32) t[i] = RGB(186, 130, 50);
            else if (r < 0.54) t[i] = RGB(232, 182, 84);
        }
        for (int i = 0; i < 20; i++)
        {
            int cx = rng.Next(16), cy = rng.Next(16);
            Px(t, cx, cy, 255, 236, 158);
            if (rng.NextDouble() < 0.55) Px(t, cx + 1, cy, 248, 212, 122);
        }
        for (int i = 0; i < 10; i++) Px(t, rng.Next(16), rng.Next(16), 158, 100, 38);
        return t;
    }

    // ---- cross-quad plant textures ----
    static int[] TexBlades(int count, int minH, int maxH, int baseG, bool tips)
    {
        int[] t = Clear();
        for (int i = 0; i < count; i++)
        {
            int x = 1 + rng.Next(14);
            int h = minH + rng.Next(maxH - minH + 1);
            int lean = rng.NextDouble() < 0.5 ? 1 : -1;
            int wide = rng.NextDouble() < 0.4 ? 1 : 0;
            int g0 = baseG + rng.Next(-16, 17);
            for (int k = 0; k < h; k++)
            {
                int yy = 15 - k;
                int xx = x + (int)Math.Round(Math.Sin(k * 0.30) * 1.1 * lean);
                int g = g0 + rng.Next(-6, 7);
                int r = (int)(g * 0.56), b = (int)(g * 0.34);
                Px(t, xx, yy, r, g, b);
                if (wide == 1 && k < h - 2) Px(t, xx + lean, yy, (int)(r * 0.9), (int)(g * 0.9), (int)(b * 0.9));
                if (k > h - 3 && tips) Px(t, xx, yy, (int)(r * 0.84), (int)(g * 0.84), (int)(b * 0.84));
            }
        }
        for (int x = 0; x < 16; x++)
        {
            if (rng.NextDouble() < 0.85) Px(t, x, 15, 68, 104, 40);
            if (rng.NextDouble() < 0.55) Px(t, x, 14, 74, 114, 44);
        }
        return t;
    }
    static int[] TexFern()
    {
        int[] t = Clear();
        int cx = 7;
        for (int y = 15; y >= 3; y--) Px(t, cx, y, 62, 108, 42);
        for (int y = 14; y >= 4; y -= 2)
        {
            int w = 1 + (15 - y) / 4;
            if (w > 4) w = 4;
            for (int k = 1; k <= w; k++)
            {
                int g = 108 + rng.Next(-12, 13);
                Px(t, cx - k, y + (k / 2), (int)(g * 0.56), g, (int)(g * 0.34));
                Px(t, cx + k, y + (k / 2), (int)(g * 0.52), g - 8, (int)(g * 0.32));
            }
        }
        return t;
    }
    static int[] TexFlower(int hr, int hg, int hb, int cr, int cg, int cb, int shape)
    {
        int[] t = Clear();
        for (int y = 15; y >= 7; y--) Px(t, 7, y, 66, 112, 44);
        Px(t, 6, 11, 82, 132, 54); Px(t, 5, 11, 74, 122, 48);
        Px(t, 8, 9, 82, 132, 54); Px(t, 9, 9, 74, 122, 48);
        if (shape == 0)          // poppy / cornflower: round head
        {
            for (int y = 3; y <= 6; y++) for (int x = 5; x <= 9; x++)
                {
                    if ((x == 5 || x == 9) && (y == 3 || y == 6)) continue;
                    Px(t, x, y, hr, hg, hb);
                }
            Px(t, 7, 4, cr, cg, cb); Px(t, 7, 5, cr, cg, cb); Px(t, 6, 5, cr, cg, cb);
        }
        else if (shape == 1)     // dandelion: small tuft
        {
            for (int y = 4; y <= 6; y++) for (int x = 5; x <= 9; x++)
                    if (rng.NextDouble() < 0.85) Px(t, x, y, hr, hg, hb);
            Px(t, 6, 3, hr, hg, hb); Px(t, 8, 3, hr, hg, hb);
            Px(t, 7, 6, cr, cg, cb);
        }
        else                      // daisy: petals around a centre
        {
            for (int y = 3; y <= 7; y++) for (int x = 5; x <= 9; x++)
                {
                    double d = Math.Sqrt((x - 7) * (x - 7) + (y - 5) * (y - 5));
                    if (d > 2.3) continue;
                    Px(t, x, y, hr, hg, hb);
                }
            Px(t, 7, 5, cr, cg, cb); Px(t, 7, 4, cr, cg, cb); Px(t, 8, 5, cr, cg, cb);
        }
        return t;
    }

    static int[] MipDown(int[] src, int size)
    {
        int ns = size / 2;
        int[] d = new int[ns * ns];
        for (int y = 0; y < ns; y++) for (int x = 0; x < ns; x++)
            {
                int aS = 0, rS = 0, gS = 0, bS = 0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
                    {
                        int c = src[(y * 2 + dy) * size + (x * 2 + dx)];
                        int a = (c >> 24) & 255;
                        aS += a; rS += ((c >> 16) & 255) * a; gS += ((c >> 8) & 255) * a; bS += (c & 255) * a;
                    }
                d[y * ns + x] = aS == 0 ? 0 : C4(aS / 4, rS / aS, gS / aS, bS / aS);
            }
        return d;
    }

    static void BuildTextures()
    {
        int[][] b = new int[NTEX][];
        b[T_GTOP] = TexGrassTop(); b[T_GSIDE] = TexGrassSide(); b[T_DIRT] = TexDirt();
        b[T_STONE] = TexStone(); b[T_LOGS] = TexLogSide(112, 86, 50, false); b[T_LOGT] = TexLogTop(162, 130, 80);
        b[T_LEAF] = TexLeaves(66, 116, 46, 3); b[T_OBS] = TexObsidian(); b[T_NRACK] = TexNetherrack();
        b[T_LAVA] = TexLava(); b[T_GLOW] = TexGlowstone(); b[T_GRAVEL] = TexGravel();
        b[T_BLOGS] = TexLogSide(216, 214, 204, true); b[T_BLOGT] = TexLogTop(200, 186, 148);
        b[T_BLEAF] = TexLeaves(96, 146, 62, 9);
        b[T_SGRASS] = TexBlades(18, 6, 12, 122, true);
        b[T_FERN] = TexFern();
        b[T_POPPY] = TexFlower(196, 42, 42, 68, 20, 20, 0);
        b[T_DANDE] = TexFlower(238, 208, 62, 200, 158, 40, 1);
        b[T_CORNF] = TexFlower(84, 108, 210, 44, 62, 150, 0);
        b[T_OXEYE] = TexFlower(238, 240, 238, 236, 202, 74, 2);
        b[T_AZURE] = TexFlower(226, 232, 240, 232, 206, 96, 2);
        b[T_TGB] = TexBlades(17, 12, 16, 116, false);
        b[T_TGT] = TexBlades(15, 5, 11, 126, true);
        b[T_COBBLE] = TexCobble();

        tex = new int[NTEX][][];
        for (int i = 0; i < NTEX; i++)
        {
            tex[i] = new int[5][];
            tex[i][0] = b[i];
            int size = 16;
            for (int l = 1; l < 5; l++) { tex[i][l] = MipDown(tex[i][l - 1], size); size /= 2; }
        }

        for (int i = 0; i < 32; i++) { topTex[i] = T_STONE; sideTex[i] = T_STONE; botTex[i] = T_STONE; }
        topTex[GRASS] = T_GTOP; sideTex[GRASS] = T_GSIDE; botTex[GRASS] = T_DIRT;
        topTex[DIRT] = T_DIRT; sideTex[DIRT] = T_DIRT; botTex[DIRT] = T_DIRT;
        topTex[STONE] = T_STONE; sideTex[STONE] = T_STONE; botTex[STONE] = T_STONE;
        topTex[COBBLE] = T_COBBLE; sideTex[COBBLE] = T_COBBLE; botTex[COBBLE] = T_COBBLE;
        topTex[LOG] = T_LOGT; sideTex[LOG] = T_LOGS; botTex[LOG] = T_LOGT;
        topTex[BLOG] = T_BLOGT; sideTex[BLOG] = T_BLOGS; botTex[BLOG] = T_BLOGT;
        topTex[LEAVES] = T_LEAF; sideTex[LEAVES] = T_LEAF; botTex[LEAVES] = T_LEAF;
        topTex[BLEAVES] = T_BLEAF; sideTex[BLEAVES] = T_BLEAF; botTex[BLEAVES] = T_BLEAF;
        topTex[OBSID] = T_OBS; sideTex[OBSID] = T_OBS; botTex[OBSID] = T_OBS;
        topTex[NRACK] = T_NRACK; sideTex[NRACK] = T_NRACK; botTex[NRACK] = T_NRACK;
        topTex[LAVA] = T_LAVA; sideTex[LAVA] = T_LAVA; botTex[LAVA] = T_LAVA;
        topTex[GLOW] = T_GLOW; sideTex[GLOW] = T_GLOW; botTex[GLOW] = T_GLOW;
        topTex[GRAVEL] = T_GRAVEL; sideTex[GRAVEL] = T_GRAVEL; botTex[GRAVEL] = T_GRAVEL;
        sideTex[SGRASS] = T_SGRASS; sideTex[FERN] = T_FERN; sideTex[POPPY] = T_POPPY;
        sideTex[DANDE] = T_DANDE; sideTex[CORNF] = T_CORNF; sideTex[OXEYE] = T_OXEYE;
        sideTex[AZURE] = T_AZURE; sideTex[TGRASS_B] = T_TGB; sideTex[TGRASS_T] = T_TGT;
    }

    // ---------------- overworld ----------------
    static void Tree(VWorld w, int x, int y, int z, int h, bool birch)
    {
        byte lg = birch ? BLEAVES : LEAVES, wd = birch ? BLOG : LOG;
        for (int i = 0; i < h; i++) w.Set(x, y + i, z, wd);
        int top = y + h;
        for (int dy = -2; dy <= 1; dy++)
        {
            int rad = (dy <= -1) ? 2 : 1;
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++)
                {
                    if (Math.Abs(dx) == rad && Math.Abs(dz) == rad && rng.NextDouble() < 0.55) continue;
                    if (dy == 1 && Math.Abs(dx) + Math.Abs(dz) > 1) continue;
                    int bx = x + dx, by = top + dy, bz = z + dz;
                    if (w.Get(bx, by, bz) == AIR) w.Set(bx, by, bz, lg);
                }
        }
    }

    static void BigTree(VWorld w, int x, int y, int z, int h)
    {
        for (int i = 0; i < h; i++) { w.Set(x, y + i, z, LOG); w.Set(x + 1, y + i, z, LOG); w.Set(x, y + i, z + 1, LOG); w.Set(x + 1, y + i, z + 1, LOG); }
        int top = y + h;
        for (int dy = -3; dy <= 1; dy++)
        {
            double rad = (dy <= -2) ? 3.4 : (dy <= 0 ? 2.8 : 1.6);
            for (int dx = -4; dx <= 5; dx++) for (int dz = -4; dz <= 5; dz++)
                {
                    double cx = dx - 0.5, cz = dz - 0.5;
                    if (Math.Sqrt(cx * cx + cz * cz) > rad + rng.NextDouble() * 0.5) continue;
                    int bx = x + dx, by = top + dy, bz = z + dz;
                    if (w.Get(bx, by, bz) == AIR) w.Set(bx, by, bz, LEAVES);
                }
        }
    }

    static void BuildOverworld()
    {
        ow = new VWorld(OX, OY, OZ);
        int[] hm = new int[OX * OZ];
        for (int z = 0; z < OZ; z++) for (int x = 0; x < OX; x++)
            {
                double n = Noise(x / 86.0, z / 86.0, 7) * 15.0
                         + Noise(x / 29.0, z / 29.0, 11) * 12.5
                         + Noise(x / 12.0, z / 12.0, 23) * 6.0
                         + Noise(x / 5.5, z / 5.5, 31) * 2.2
                         + Noise(x / 2.7, z / 2.7, 37) * 0.9;
                double h = (GY - 1) + (n - 18.3);
                double dcx = x - 160.0, dcz = z - 166.0;
                double d = Math.Sqrt(dcx * dcx + dcz * dcz * 0.8);
                double flat = Sat((d - 13.0) / 26.0);
                flat = flat * flat * (3 - 2 * flat);
                h = (GY - 1) * (1 - flat) + h * flat;
                int hi = (int)Math.Round(h);
                if (hi < 6) hi = 6; if (hi > OY - 14) hi = OY - 14;
                hm[z * OX + x] = hi;
                for (int y = 0; y <= hi; y++)
                {
                    byte bb = STONE;
                    if (y == hi) bb = GRASS; else if (y > hi - 4) bb = DIRT;
                    ow.Set(x, y, z, bb);
                }
            }

        // gravel + stone patches
        for (int i = 0; i < 40; i++)
        {
            int px = 20 + rng.Next(OX - 40), pz = 20 + rng.Next(OZ - 40);
            int rad = 2 + rng.Next(4);
            byte mat = rng.NextDouble() < 0.5 ? GRAVEL : COBBLE;
            for (int z = pz - rad; z <= pz + rad; z++) for (int x = px - rad; x <= px + rad; x++)
                {
                    if (x < 0 || z < 0 || x >= OX || z >= OZ) continue;
                    if ((x - px) * (x - px) + (z - pz) * (z - pz) > rad * rad) continue;
                    int hi = hm[z * OX + x];
                    if (ow.Get(x, hi, z) == GRASS) ow.Set(x, hi, z, mat);
                }
        }

        // boulders
        for (int i = 0; i < 26; i++)
        {
            int px = 20 + rng.Next(OX - 40), pz = 20 + rng.Next(OZ - 40);
            double dcx = px - 160.0, dcz = pz - 166.0;
            if (dcx * dcx + dcz * dcz < 260) continue;
            int hi = hm[pz * OX + px];
            int rad = 1 + rng.Next(2);
            for (int y = 0; y <= rad; y++) for (int z = -rad; z <= rad; z++) for (int x = -rad; x <= rad; x++)
                    {
                        if (x * x + y * y + z * z > rad * rad + 1) continue;
                        if (rng.NextDouble() < 0.2) continue;
                        ow.Set(px + x, hi + 1 + y, pz + z, COBBLE);
                    }
        }

        // forest: clumped by noise, oak and birch, a few big oaks
        for (int i = 0; i < 4200; i++)
        {
            int x = 8 + rng.Next(OX - 16), z = 8 + rng.Next(OZ - 16);
            double dx = x - 160.0, dz = z - 166.0;
            double dc = Math.Sqrt(dx * dx + dz * dz);
            if (dc < 19) continue;
            if (x > 150 && x < 172 && z > 166 && z < 196) continue;   // keep the portal sightline clear
            double dens = Sat((Noise(x / 46.0, z / 46.0, 61) - 0.36) / 0.42);
            double near = Sat((dc - 19) / 26.0);
            if (rng.NextDouble() > dens * dens * 1.15 * near) continue;
            int hi = hm[z * OX + x];
            if (ow.Get(x, hi, z) != GRASS) continue;
            if (ow.Get(x, hi + 1, z) != AIR) continue;
            double roll = rng.NextDouble();
            if (roll < 0.08 && dc > 30) BigTree(ow, x, hi + 1, z, 7 + rng.Next(5));
            else Tree(ow, x, hi + 1, z, 4 + rng.Next(6), roll > 0.78);
        }

        // fallen log, foreground left
        int flz = 163, flx = 150, flh = hm[flz * OX + flx];
        for (int k = 0; k < 5; k++) ow.Set(flx + k, flh + 1, flz - k / 3, LOG);

        // ------- the obsidian portal -------
        int fx0 = 157, fx1 = 162, fy0 = GY, fy1 = GY + 6, pz2 = 170;
        for (int y = fy0; y <= fy1; y++) for (int x = fx0; x <= fx1; x++)
            {
                bool ring = (x == fx0 || x == fx1 || y == fy0 || y == fy1);
                ow.Set(x, y, pz2, ring ? OBSID : PORTAL);
            }

        PlacePlants(hm);
    }

    static void PlacePlants(int[] hm)
    {
        for (int z = 2; z < OZ - 2; z++) for (int x = 2; x < OX - 2; x++)
            {
                int hi = hm[z * OX + x];
                if (ow.Get(x, hi, z) != GRASS) continue;
                if (ow.Get(x, hi + 1, z) != AIR) continue;
                // thin the planting out right in front of the lens
                double cdx = x - 157.2, cdz = z - 159.0;
                double cdist = Math.Sqrt(cdx * cdx + cdz * cdz);
                if (cdist < 4.0) continue;
                double nearFade = Sat((cdist - 4.0) / 7.0);

                double lush = Sat((Noise(x / 19.0, z / 19.0, 71) - 0.26) / 0.46);
                double r = rng.NextDouble();
                double grassChance = (0.06 + lush * 0.58) * nearFade;
                bool sight = x >= 148 && x <= 172 && z >= 156 && z <= 182;
                if (r < grassChance)
                {
                    if (rng.NextDouble() < 0.05 && !sight && ow.Get(x, hi + 2, z) == AIR)
                    { ow.Set(x, hi + 1, z, TGRASS_B); ow.Set(x, hi + 2, z, TGRASS_T); }
                    else ow.Set(x, hi + 1, z, rng.NextDouble() < 0.17 ? FERN : SGRASS);
                    continue;
                }
                // flowers, each species clumped by its own noise field
                double fp = Noise(x / 9.0, z / 9.0, 101);
                double fd = Noise(x / 11.0, z / 11.0, 103);
                double fc = Noise(x / 8.0, z / 8.0, 107);
                double fo = Noise(x / 10.0, z / 10.0, 109);
                double fa = Noise(x / 7.0, z / 7.0, 113);
                double pick = rng.NextDouble() / Math.Max(0.15, nearFade);
                if (fp > 0.80 && pick < 0.30) ow.Set(x, hi + 1, z, POPPY);
                else if (fd > 0.80 && pick < 0.30) ow.Set(x, hi + 1, z, DANDE);
                else if (fc > 0.83 && pick < 0.26) ow.Set(x, hi + 1, z, CORNF);
                else if (fo > 0.83 && pick < 0.26) ow.Set(x, hi + 1, z, OXEYE);
                else if (fa > 0.85 && pick < 0.22) ow.Set(x, hi + 1, z, AZURE);
            }

        // leafy shrubs
        for (int i = 0; i < 700; i++)
        {
            int x = 6 + rng.Next(OX - 12), z = 6 + rng.Next(OZ - 12);
            double dx = x - 157.2, dz = z - 159.0;
            if (dx * dx + dz * dz < 90) continue;
            if (x >= 146 && x <= 174 && z >= 154 && z <= 186) continue;   // clear of the portal sightline
            int hi = hm[z * OX + x];
            if (ow.Get(x, hi, z) != GRASS) continue;
            if (ow.Get(x, hi + 1, z) != AIR && !IsCross(ow.Get(x, hi + 1, z))) continue;
            ow.Set(x, hi + 1, z, LEAVES);
            if (rng.NextDouble() < 0.45) ow.Set(x + 1, hi + 1, z, LEAVES);
            if (rng.NextDouble() < 0.35) ow.Set(x, hi + 1, z + 1, LEAVES);
            if (rng.NextDouble() < 0.22) ow.Set(x, hi + 2, z, LEAVES);
        }
    }

    // ---------------- nether ----------------
    static void Mound(int[] top, double cx, double cz, double rx, double rz, double hgt, int seed)
    {
        for (int z = (int)(cz - rz) - 1; z <= (int)(cz + rz) + 1; z++)
            for (int x = (int)(cx - rx) - 1; x <= (int)(cx + rx) + 1; x++)
            {
                if (x < 0 || z < 0 || x >= NX || z >= NZ) continue;
                double ddx = (x - cx) / rx, ddz = (z - cz) / rz;
                double t = 1.0 - Math.Sqrt(ddx * ddx + ddz * ddz);
                if (t <= 0) continue;
                int h = 22 + (int)Math.Round(Math.Pow(Sat(t), 0.5) * hgt + Noise(x / 4.0, z / 4.0, seed) * 2.8 - 1.2);
                if (h > 44) h = 44;
                int cur = top[z * NX + x];
                if (h > cur) { for (int y = cur + 1; y <= h; y++) nw.Set(x, y, z, NRACK); top[z * NX + x] = h; }
            }
    }
    static void Spire(int[] top, double cx, double cz, double rad, int hgt, int seed)
    {
        for (int z = (int)(cz - rad) - 1; z <= (int)(cz + rad) + 1; z++)
            for (int x = (int)(cx - rad) - 1; x <= (int)(cx + rad) + 1; x++)
            {
                if (x < 0 || z < 0 || x >= NX || z >= NZ) continue;
                double ddx = (x - cx) / rad, ddz = (z - cz) / rad;
                if (ddx * ddx + ddz * ddz > 1.1) continue;
                int h = hgt + (int)Math.Round(Noise(x / 3.0, z / 3.0, seed) * 2.6 - 1.3);
                for (int y = 23; y <= h; y++) nw.Set(x, y, z, NRACK);
                if (h > top[z * NX + x]) top[z * NX + x] = h;
            }
    }

    static void BuildNether()
    {
        nw = new VWorld(NX, NY, NZ);
        for (int i = 0; i < nw.B.Length; i++) nw.B[i] = NRACK;

        int[] top = new int[NX * NZ];
        for (int z = 0; z < NZ; z++) for (int x = 0; x < NX; x++)
            {
                int ceil = 43 + (int)Math.Round(Noise(x / 13.0, z / 13.0, 91) * 5.0 - 2.5);
                top[z * NX + x] = 22;
                for (int y = 23; y < ceil; y++) nw.Set(x, y, z, AIR);
            }

        Mound(top, 80.0, 88.0, 7.0, 6.0, 8.0, 141);
        Mound(top, 63.5, 95.0, 5.5, 6.5, 5.5, 151);
        Mound(top, 70.0, 104.0, 5.5, 5.0, 6.0, 157);
        Mound(top, 74.0, 120.0, 36.0, 13.0, 16.0, 163);

        for (int z = 75; z <= 81; z++) for (int x = 66; x <= 73; x++)
            {
                double ddx = (x - 69.6) / 1.8, ddz = (z - 78.0) / 1.8;
                if (ddx * ddx + ddz * ddz > 1.15) continue;
                int h = 30 + (int)Math.Round(Noise(x / 3.0, z / 3.0, 133) * 2.2 - 1.1);
                for (int y = 23; y <= h; y++) nw.Set(x, y, z, NRACK);
                top[z * NX + x] = h;
            }
        for (int z = 86; z <= 92; z++) for (int x = 64; x <= 70; x++)
            {
                double ddx = (x - 67.0) / 2.2, ddz = (z - 89.0) / 2.2;
                if (ddx * ddx + ddz * ddz > 1.15) continue;
                int h = 27 + (int)Math.Round(Noise(x / 3.0, z / 3.0, 139) * 2.4 - 1.2);
                for (int y = 23; y <= h; y++) nw.Set(x, y, z, NRACK);
                top[z * NX + x] = h;
            }
        Spire(top, 65.5, 91.0, 1.4, 33, 145);
        Spire(top, 69.0, 100.0, 1.9, 32, 149);
        Spire(top, 64.0, 109.0, 1.7, 31, 153);

        for (int z = 82; z < 112; z++) for (int x = 0; x < NX; x++)
            {
                if (top[z * NX + x] != 22) continue;
                double n = Noise(x / 9.0, z / 9.0, 171);
                double edge = Sat((z - 82) / 4.0) * Sat((110 - z) / 4.0);
                if (n * 0.55 + edge * 0.75 > 0.44) { nw.Set(x, 22, z, LAVA); nw.Set(x, 21, z, LAVA); }
            }

        for (int x = 69; x <= 70; x++)
        {
            int h = top[76 * NX + x]; if (h < 26) h = 29;
            for (int y = 23; y <= h; y++) nw.Set(x, y, 75, LAVA);
        }
        for (int x = 67; x <= 72; x++) for (int z = 75; z <= 81; z++)
                if (top[z * NX + x] >= 28) nw.Set(x, top[z * NX + x], z, LAVA);
        for (int x = 64; x <= 75; x++) for (int z = 70; z <= 78; z++)
                if (top[z * NX + x] == 22) { nw.Set(x, 22, z, LAVA); nw.Set(x, 21, z, LAVA); }

        for (int x = 72; x < 88; x++) for (int z = 82; z < 95; z++)
            {
                int h = top[z * NX + x];
                if (h > 24 && Noise(x / 3.5, z / 3.5, 181) > 0.74) nw.Set(x, h, z, GLOW);
            }
        for (int x = 59; x < 71; x++) for (int z = 87; z < 100; z++)
            {
                int h = top[z * NX + x];
                if (h > 24 && Noise(x / 3.5, z / 3.5, 187) > 0.76) nw.Set(x, h, z, GLOW);
            }

        for (int z = 62; z < NZ; z++) for (int x = 34; x < 104; x++)
            {
                for (int y = NY - 3; y > 26; y--)
                {
                    if (nw.Get(x, y, z) == NRACK && nw.Get(x, y - 1, z) == AIR)
                    {
                        double g = Noise(x / 4.0, z / 4.0, 191);
                        if (g > 0.86) nw.Set(x, y, z, GLOW);
                        else if (g < 0.22 && rng.NextDouble() < 0.5)
                        {
                            int len = 3 + rng.Next(9);
                            for (int k = 1; k <= len; k++) nw.Set(x, y - k, z, NRACK);
                            if (rng.NextDouble() < 0.72)
                            {
                                nw.Set(x, y - len, z, GLOW);
                                if (rng.NextDouble() < 0.5) nw.Set(x, y - len + 1, z, GLOW);
                            }
                        }
                        break;
                    }
                }
            }

        for (int z = 58; z <= 70; z++) for (int x = 56; x <= 72; x++)
            {
                for (int y = 23; y <= top[z * NX + x]; y++) nw.Set(x, y, z, AIR);
                top[z * NX + x] = 22; nw.Set(x, 22, z, NRACK);
            }
        int fx0 = 61, fx1 = 66, fy0 = 23, fy1 = 29, pz2 = 64;
        for (int y = fy0; y <= fy1; y++) for (int x = fx0; x <= fx1; x++)
            {
                bool ring = (x == fx0 || x == fx1 || y == fy0 || y == fy1);
                nw.Set(x, y, pz2, ring ? OBSID : PORTAL);
            }
    }

    // ---------------- lighting ----------------
    static void ComputeLight(VWorld w, bool hasSky)
    {
        int n = w.B.Length;
        for (int i = 0; i < n; i++) { w.Blk[i] = Emission(w.B[i]); w.Sky[i] = 0; }
        if (hasSky)
        {
            for (int z = 0; z < w.SZ; z++) for (int x = 0; x < w.SX; x++)
                {
                    int lvl = 15;
                    for (int y = w.SY - 1; y >= 0; y--)
                    {
                        byte b = w.B[w.I(x, y, z)];
                        if (LightBlocking(b)) break;
                        lvl -= LightOpacity(b);
                        if (lvl <= 0) break;
                        w.Sky[w.I(x, y, z)] = (byte)lvl;
                    }
                }
        }
        Spread(w, w.Blk);
        if (hasSky) Spread(w, w.Sky);
    }

    static void Spread(VWorld w, byte[] L)
    {
        int SX = w.SX, SY = w.SY, SZ = w.SZ;
        byte[] B = w.B;
        for (int lvl = 15; lvl >= 2; lvl--)
        {
            for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++)
                {
                    int row = (y * SZ + z) * SX;
                    for (int x = 0; x < SX; x++)
                    {
                        if (L[row + x] != lvl) continue;
                        for (int d = 0; d < 6; d++)
                        {
                            int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                            int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                            int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                            if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
                            int ni = (ny * SZ + nz) * SX + nx;
                            byte nb = B[ni];
                            if (LightBlocking(nb)) continue;
                            int nl = lvl - 1 - LightOpacity(nb);
                            if (nl > 0 && L[ni] < nl) L[ni] = (byte)nl;
                        }
                    }
                }
        }
    }

    // ---------------- texture sampling ----------------
    static int SampleTex(int id, double u, double v, double lod)
    {
        if (lod < 0) lod = 0; if (lod > 4) lod = 4;
        int l0 = (int)lod; if (l0 > 4) l0 = 4;
        int l1 = l0 + 1; if (l1 > 4) l1 = 4;
        double f = lod - l0;
        int s0 = 16 >> l0, s1 = 16 >> l1;
        int u0 = (int)(u * s0); if (u0 < 0) u0 = 0; if (u0 >= s0) u0 = s0 - 1;
        int v0 = (int)(v * s0); if (v0 < 0) v0 = 0; if (v0 >= s0) v0 = s0 - 1;
        int u1 = (int)(u * s1); if (u1 < 0) u1 = 0; if (u1 >= s1) u1 = s1 - 1;
        int v1 = (int)(v * s1); if (v1 < 0) v1 = 0; if (v1 >= s1) v1 = s1 - 1;
        int c0 = tex[id][l0][v0 * s0 + u0];
        int c1 = tex[id][l1][v1 * s1 + u1];
        int a = (int)(((c0 >> 24) & 255) * (1 - f) + ((c1 >> 24) & 255) * f);
        int r = (int)(((c0 >> 16) & 255) * (1 - f) + ((c1 >> 16) & 255) * f);
        int g = (int)(((c0 >> 8) & 255) * (1 - f) + ((c1 >> 8) & 255) * f);
        int b = (int)((c0 & 255) * (1 - f) + (c1 & 255) * f);
        return C4(a, r, g, b);
    }

    // ---------------- ray marching ----------------
    // returns: hit cell, face axis/sign (ax = -1 for a cross-quad hit), and exact distance
    static bool Trace(VWorld w, double ox, double oy, double oz, double dx, double dy, double dz,
                      double maxT, out int hx, out int hy, out int hz, out int ax, out int sg, out double tHit)
    {
        hx = 0; hy = 0; hz = 0; ax = -1; sg = 0; tHit = 0;
        int ix = (int)Math.Floor(ox), iy = (int)Math.Floor(oy), iz = (int)Math.Floor(oz);
        int stx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sty = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double INF = 1e30;
        double tdx = dx != 0 ? Math.Abs(1.0 / dx) : INF;
        double tdy = dy != 0 ? Math.Abs(1.0 / dy) : INF;
        double tdz = dz != 0 ? Math.Abs(1.0 / dz) : INF;
        double tmx = dx > 0 ? (ix + 1 - ox) / dx : (dx < 0 ? (ix - ox) / dx : INF);
        double tmy = dy > 0 ? (iy + 1 - oy) / dy : (dy < 0 ? (iy - oy) / dy : INF);
        double tmz = dz > 0 ? (iz + 1 - oz) / dz : (dz < 0 ? (iz - oz) / dz : INF);
        double t = 0;
        int axis = -1, sgn = 0;
        int SX = w.SX, SY = w.SY, SZ = w.SZ;
        byte[] B = w.B;

        for (int step = 0; step < 2200; step++)
        {
            if (ix < 0 || iy < 0 || iz < 0 || ix >= SX || iy >= SY || iz >= SZ) return false;
            double tExit = tmx < tmy ? (tmx < tmz ? tmx : tmz) : (tmy < tmz ? tmy : tmz);
            byte b = B[(iy * SZ + iz) * SX + ix];

            if (b >= 20)   // cross-quad plant
            {
                double bt = -1; double bu = 0, bv = 0;
                for (int q = 0; q < 2; q++)
                {
                    double den = (q == 0) ? (dx - dz) : (dx + dz);
                    if (Math.Abs(den) < 1e-9) continue;
                    double num = (q == 0) ? ((oz - iz) - (ox - ix)) : (1.0 - (ox - ix) - (oz - iz));
                    double qt = num / den;
                    if (qt < t || qt > tExit) continue;
                    double lx = ox + dx * qt - ix, ly = oy + dy * qt - iy, lz = oz + dz * qt - iz;
                    if (lx < 0 || lx > 1 || ly < 0 || ly > 1 || lz < 0 || lz > 1) continue;
                    if (bt >= 0 && qt >= bt) continue;
                    bt = qt; bu = lx; bv = 1 - ly;
                }
                if (bt >= 0)
                {
                    double lod = Math.Log(Math.Max(1e-6, 16.0 * bt * pixAng), 2.0);
                    int c = SampleTex(sideTex[b], bu, bv, lod);
                    if (((c >> 24) & 255) >= 128)
                    { hx = ix; hy = iy; hz = iz; ax = -1; sg = 0; tHit = bt; return true; }
                }
            }
            else if (b == LEAVES || b == BLEAVES)
            {
                if (axis >= 0)
                {
                    double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
                    double u, v;
                    if (axis == 1) { u = px - ix; v = pz - iz; }
                    else if (axis == 0) { u = pz - iz; v = 1 - (py - iy); }
                    else { u = px - ix; v = 1 - (py - iy); }
                    double lod = Math.Log(Math.Max(1e-6, 16.0 * t * pixAng), 2.0);
                    int c = SampleTex(sideTex[b], u, v, lod);
                    if (((c >> 24) & 255) >= 128)
                    { hx = ix; hy = iy; hz = iz; ax = axis; sg = sgn; tHit = t; return true; }
                }
            }
            else if (b != AIR && b != PORTAL)
            {
                hx = ix; hy = iy; hz = iz; ax = axis; sg = sgn; tHit = t; return true;
            }

            if (tmx < tmy && tmx < tmz) { t = tmx; ix += stx; tmx += tdx; axis = 0; sgn = -stx; }
            else if (tmy < tmz) { t = tmy; iy += sty; tmy += tdy; axis = 1; sgn = -sty; }
            else { t = tmz; iz += stz; tmz += tdz; axis = 2; sgn = -stz; }
            if (t > maxT) return false;
        }
        return false;
    }

    static double Curve(double lvl) { double f = lvl / 15.0; return f / (4.0 - 3.0 * f); }

    static void SkyColor(double dy, out double r, out double g, out double b)
    {
        double t = Sat(dy);
        double hz = Math.Pow(1 - t, 2.6);
        r = (100 + (196 - 100) * hz) / 255.0;
        g = (152 + (216 - 152) * hz) / 255.0;
        b = (244 + (238 - 244) * hz) / 255.0;
    }

    // vanilla-style blocky clouds on a plane above the camera
    static bool CloudAt(double wx, double wz)
    {
        double cx = Math.Floor(wx / 12.0), cz = Math.Floor(wz / 12.0);
        double n = Noise(cx / 5.0, cz / 5.0, 301) * 0.68 + Noise(cx / 1.7, cz / 1.7, 307) * 0.32;
        return n > 0.545;
    }

    static void ApplySky(double dx, double dy, double dz, out double r, out double g, out double b)
    {
        SkyColor(dy, out r, out g, out b);
        if (dy > 0.05)
        {
            double t = 118.0 / dy;
            if (t < 1400)
            {
                double wx = camP[0] + dx * t, wz = camP[2] + dz * t;
                if (CloudAt(wx, wz))
                {
                    double fade = 1.0 - Sat((t - 170.0) / 620.0);
                    double a = 0.90 * fade;
                    double cr = 0.905, cg = 0.925, cb = 0.965;
                    // a touch of shading so the underside isn't pure white
                    double edge = CloudAt(wx + 6, wz) && CloudAt(wx - 6, wz) ? 1.0 : 0.93;
                    r += (cr * edge - r) * a; g += (cg * edge - g) * a; b += (cb * edge - b) * a;
                }
            }
        }
    }

    static void FaceLight(VWorld w, int hx, int hy, int hz, int ax, int sg, double fu, double fv,
                          out double skyL, out double blkL, out double ao)
    {
        int nx = hx, ny = hy, nz = hz;
        if (ax == 0) nx += sg; else if (ax == 1) ny += sg; else nz += sg;
        int ua = (ax == 0) ? 2 : 0;
        int va = (ax == 1) ? 2 : 1;
        skyL = 0; blkL = 0; ao = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++)
            {
                int du = (i == 0) ? -1 : 1, dv = (j == 0) ? -1 : 1;
                int sx1 = nx, sy1 = ny, sz1 = nz;
                if (ua == 0) sx1 += du; else if (ua == 1) sy1 += du; else sz1 += du;
                int sx2 = nx, sy2 = ny, sz2 = nz;
                if (va == 0) sx2 += dv; else if (va == 1) sy2 += dv; else sz2 += dv;
                int cx = sx1, cy = sy1, cz = sz1;
                if (va == 0) cx += dv; else if (va == 1) cy += dv; else cz += dv;

                bool o1 = Solid(w.Get(sx1, sy1, sz1));
                bool o2 = Solid(w.Get(sx2, sy2, sz2));
                bool oc = Solid(w.Get(cx, cy, cz));
                int aoV = (o1 && o2) ? 0 : (3 - (o1 ? 1 : 0) - (o2 ? 1 : 0) - (oc ? 1 : 0));

                double s = w.SkyL(nx, ny, nz), bl = w.BlkL(nx, ny, nz); int cnt = 1;
                if (!o1) { s += w.SkyL(sx1, sy1, sz1); bl += w.BlkL(sx1, sy1, sz1); cnt++; }
                if (!o2) { s += w.SkyL(sx2, sy2, sz2); bl += w.BlkL(sx2, sy2, sz2); cnt++; }
                if (!oc && !(o1 && o2)) { s += w.SkyL(cx, cy, cz); bl += w.BlkL(cx, cy, cz); cnt++; }
                s /= cnt; bl /= cnt;

                double ww = ((i == 0) ? (1 - fu) : fu) * ((j == 0) ? (1 - fv) : fv);
                skyL += s * ww; blkL += bl * ww; ao += aoV * ww;
            }
    }

    static void LightMap(double skyL, double blkL, bool nether, double aoF,
                         out double lr, out double lg, out double lb)
    {
        double sc = Curve(skyL) * (nether ? 0.0 : 1.0);
        double bc = Curve(blkL);
        double sr = sc * 1.0, sg2 = sc * 0.995, sb = sc * 0.965;
        double br = bc * 1.0, bg = bc * 0.72, bb = bc * 0.44;
        lr = 1 - (1 - sr) * (1 - br); lg = 1 - (1 - sg2) * (1 - bg); lb = 1 - (1 - sb) * (1 - bb);
        if (nether) { lr += 0.165; lg += 0.068; lb += 0.052; }
        else { lr += 0.045; lg += 0.048; lb += 0.058; }
        lr *= aoF; lg *= aoF; lb *= aoF;
    }

    static void Shade(VWorld w, bool nether, int hx, int hy, int hz, int ax, int sg,
                      double px, double py, double pz, double lod,
                      out double outR, out double outG, out double outB)
    {
        byte b = w.B[w.I(hx, hy, hz)];
        double fx = px - hx, fy = py - hy, fz = pz - hz;

        if (IsCross(b))     // plant: flat-lit billboard, no face shading, no AO
        {
            int c = SampleTex(sideTex[b], fx, 1 - fy, lod);
            double cr = ((c >> 16) & 255) / 255.0, cg = ((c >> 8) & 255) / 255.0, cb = (c & 255) / 255.0;
            double lr, lg, lb;
            LightMap(w.SkyL(hx, hy, hz), w.BlkL(hx, hy, hz), nether, 1.0, out lr, out lg, out lb);
            outR = cr * lr; outG = cg * lg; outB = cb * lb;
            return;
        }

        int texId;
        if (ax == 1) texId = (sg > 0) ? topTex[b] : botTex[b]; else texId = sideTex[b];
        double u, v;
        if (ax == 1) { u = fx; v = fz; }
        else if (ax == 0) { u = fz; v = 1 - fy; }
        else { u = fx; v = 1 - fy; }

        int cc = SampleTex(texId, u, v, lod);
        double r2 = ((cc >> 16) & 255) / 255.0, g2 = ((cc >> 8) & 255) / 255.0, b2 = (cc & 255) / 255.0;
        double face = (ax == 1) ? (sg > 0 ? 1.0 : 0.5) : (ax == 2 ? 0.8 : 0.6);

        double lr2, lg2, lb2;
        if (Emissive(b))
        {
            lr2 = 1.0; lg2 = 0.94; lb2 = 0.86;
            face = (ax == 1) ? (sg > 0 ? 1.0 : 0.66) : (ax == 2 ? 0.87 : 0.76);
        }
        else
        {
            double fu, fv;
            if (ax == 1) { fu = fx; fv = fz; } else if (ax == 0) { fu = fz; fv = fy; } else { fu = fx; fv = fy; }
            double skyL, blkL, ao;
            FaceLight(w, hx, hy, hz, ax, sg, fu, fv, out skyL, out blkL, out ao);
            LightMap(skyL, blkL, nether, 0.55 + 0.45 * (ao / 3.0), out lr2, out lg2, out lb2);
        }
        outR = r2 * face * lr2; outG = g2 * face * lg2; outB = b2 * face * lb2;
    }

    static void Norm(double[] v)
    {
        double l = Math.Sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= l; v[1] /= l; v[2] /= l;
    }
    static double[] Cross3(double[] a, double[] b)
    {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    static void SetupCamera()
    {
        camP = new double[] { 157.2, GY + 1.62, 159.0 };
        double[] target = new double[] { 160.0, 23.4, PZ };
        camF = new double[] { target[0] - camP[0], target[1] - camP[1], target[2] - camP[2] };
        Norm(camF);
        camR = Cross3(new double[] { 0, 1, 0 }, camF); Norm(camR);
        camU = Cross3(camF, camR); Norm(camU);
        tanHalf = Math.Tan(FOV * Math.PI / 360.0);
        pixAng = 2.0 * tanHalf / H;
    }

    static void RenderScene()
    {
        double fogA = 62, fogB = 178;
        double nfogA = 13, nfogB = 76;
        Parallel.For(0, H, py =>
        {
            for (int px = 0; px < W; px++)
            {
                double sx = (px + 0.5 - W * 0.5) / (H * 0.5) * tanHalf;
                double sy = -(py + 0.5 - H * 0.5) / (H * 0.5) * tanHalf;
                double dx = camF[0] + camR[0] * sx + camU[0] * sy;
                double dy = camF[1] + camR[1] * sx + camU[1] * sy;
                double dz = camF[2] + camR[2] * sx + camU[2] * sy;
                double dl = Math.Sqrt(dx * dx + dy * dy + dz * dz);
                dx /= dl; dy /= dl; dz /= dl;

                double tPortal = 1e30;
                if (dz > 1e-9)
                {
                    double tp = (PZ - camP[2]) / dz;
                    if (tp > 0)
                    {
                        double hxp = camP[0] + dx * tp, hyp = camP[1] + dy * tp;
                        if (hxp > AX0 && hxp < AX1 && hyp > AY0 && hyp < AY1) tPortal = tp;
                    }
                }

                int hx, hy, hz, ax, sg; double tHit;
                double r = 0, g = 0, b = 0, dist;
                bool through = false;
                bool hit = Trace(ow, camP[0], camP[1], camP[2], dx, dy, dz, tPortal,
                                 out hx, out hy, out hz, out ax, out sg, out tHit);

                if (hit)
                {
                    double hpx = camP[0] + dx * tHit, hpy = camP[1] + dy * tHit, hpz = camP[2] + dz * tHit;
                    double lod = Math.Log(Math.Max(1e-6, 16.0 * tHit * pixAng), 2.0);
                    Shade(ow, false, hx, hy, hz, ax, sg, hpx, hpy, hpz, lod, out r, out g, out b);
                    dist = tHit;
                    double fr, fgc, fbc; ApplySky(dx, dy, dz, out fr, out fgc, out fbc);
                    double f = Sat((dist - fogA) / (fogB - fogA));
                    f = f * f;
                    r += (fr - r) * f; g += (fgc - g) * f; b += (fbc - b) * f;
                }
                else if (tPortal < 1e29)
                {
                    through = true;
                    double ox = camP[0] + dx * tPortal + DX;
                    double oy = camP[1] + dy * tPortal + DY;
                    double oz = camP[2] + dz * tPortal + DZ + 1e-4;
                    int nhx, nhy, nhz, nax, nsg; double ntHit;
                    bool nhit = Trace(nw, ox, oy, oz, dx, dy, dz, 1e29,
                                      out nhx, out nhy, out nhz, out nax, out nsg, out ntHit);
                    double total = tPortal + ntHit;
                    if (nhit)
                    {
                        double hpx = ox + dx * ntHit, hpy = oy + dy * ntHit, hpz = oz + dz * ntHit;
                        double lod = Math.Log(Math.Max(1e-6, 16.0 * total * pixAng), 2.0);
                        Shade(nw, true, nhx, nhy, nhz, nax, nsg, hpx, hpy, hpz, lod, out r, out g, out b);
                        dist = total;
                    }
                    else { r = 0.26; g = 0.062; b = 0.052; dist = 1e9; }
                    double f = Sat((dist - nfogA) / (nfogB - nfogA));
                    r += (0.26 - r) * f; g += (0.062 - g) * f; b += (0.052 - b) * f;
                    dist = total;
                }
                else
                {
                    ApplySky(dx, dy, dz, out r, out g, out b);
                    dist = 1e30;
                }

                int idx = py * W + px;
                depth[idx] = dist; thru[idx] = through;
                fb[idx] = (Cl((int)(Sat(r) * 255 + 0.5)) << 16)
                        | (Cl((int)(Sat(g) * 255 + 0.5)) << 8)
                        | Cl((int)(Sat(b) * 255 + 0.5));
            }
        });
    }

    // ---------------- overlays ----------------
    static bool Project(double wx, double wy, double wz, double[] org, out double sx, out double sy, out double dist)
    {
        double vx = wx - org[0], vy = wy - org[1], vz = wz - org[2];
        double f = vx * camF[0] + vy * camF[1] + vz * camF[2];
        dist = Math.Sqrt(vx * vx + vy * vy + vz * vz);
        sx = 0; sy = 0;
        if (f <= 0.01) return false;
        double rr = vx * camR[0] + vy * camR[1] + vz * camR[2];
        double uu = vx * camU[0] + vy * camU[1] + vz * camU[2];
        sx = W * 0.5 + (rr / f / tanHalf) * (H * 0.5);
        sy = H * 0.5 - (uu / f / tanHalf) * (H * 0.5);
        return true;
    }
    static void Put(int x, int y, int r, int g, int b)
    {
        if (x < 0 || y < 0 || x >= W || y >= H) return;
        fb[y * W + x] = (Cl(r) << 16) | (Cl(g) << 8) | Cl(b);
    }
    static void PutA(int x, int y, int r, int g, int b, double a)
    {
        if (x < 0 || y < 0 || x >= W || y >= H) return;
        int c = fb[y * W + x];
        int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
        Put(x, y, (int)(cr + (r - cr) * a), (int)(cg + (g - cg) * a), (int)(cb + (b - cb) * a));
    }

    static void DrawSun()
    {
        double sdx = camF[0] - 0.62 * camR[0] + 0.40 * camU[0];
        double sdy = camF[1] - 0.62 * camR[1] + 0.40 * camU[1];
        double sdz = camF[2] - 0.62 * camR[2] + 0.40 * camU[2];
        double l = Math.Sqrt(sdx * sdx + sdy * sdy + sdz * sdz); sdx /= l; sdy /= l; sdz /= l;
        double ssx, ssy, sd;
        if (!Project(camP[0] + sdx * 300, camP[1] + sdy * 300, camP[2] + sdz * 300, camP, out ssx, out ssy, out sd)) return;
        int size = 118, half = size / 2;
        int cx = (int)ssx, cy = (int)ssy;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            {
                int fxp = cx - half + x, fyp = cy - half + y;
                if (fxp < 0 || fyp < 0 || fxp >= W || fyp >= H) continue;
                if (depth[fyp * W + fxp] < 1e29) continue;
                int tx = x * 16 / size, ty = y * 16 / size;
                int r = 255, g = 250, b = 224;
                if (tx == 0 || ty == 0 || tx == 15 || ty == 15) { r = 252; g = 240; b = 200; }
                Put(fxp, fyp, r, g, b);
            }
    }

    static void DrawParticles()
    {
        for (int i = 0; i < 130; i++)
        {
            double t = rng.NextDouble();
            double x, y, z;
            if (t < 0.45)
            {
                x = AX0 + rng.NextDouble() * (AX1 - AX0);
                y = AY0 + rng.NextDouble() * (AY1 - AY0);
                z = PZ + (rng.NextDouble() - 0.5) * 2.6;
            }
            else
            {
                x = AX0 - 1.4 + rng.NextDouble() * (AX1 - AX0 + 2.8);
                y = AY0 - 1.2 + rng.NextDouble() * (AY1 - AY0 + 2.6);
                z = PZ - 0.4 + (rng.NextDouble() - 0.5) * 4.5;
            }
            double sx, sy, d;
            if (!Project(x, y, z, camP, out sx, out sy, out d)) continue;
            int ix = (int)sx, iy = (int)sy;
            if (ix < 0 || iy < 0 || ix >= W || iy >= H) continue;
            if (depth[iy * W + ix] < d - 0.06) continue;
            int sz = (int)Math.Max(3, Math.Round(16.0 / d));
            double c = rng.NextDouble();
            int r, g, b;
            if (c < 0.35) { r = 206; g = 116; b = 244; }
            else if (c < 0.68) { r = 160; g = 70; b = 210; }
            else if (c < 0.88) { r = 232; g = 180; b = 255; }
            else { r = 128; g = 52; b = 178; }
            double a = 0.6 + rng.NextDouble() * 0.35;
            for (int dy2 = 0; dy2 < sz; dy2++) for (int dx2 = 0; dx2 < sz; dx2++)
                    PutA(ix + dx2, iy + dy2, r, g, b, a);
        }
        double[] vcam = new double[] { camP[0] + DX, camP[1] + DY, camP[2] + DZ };
        for (int i = 0; i < 420; i++)
        {
            double x = 52 + rng.NextDouble() * 34;
            double y = 23 + rng.NextDouble() * 12;
            double z = 66 + rng.NextDouble() * 26;
            double sx, sy, d;
            if (!Project(x, y, z, vcam, out sx, out sy, out d)) continue;
            int ix = (int)sx, iy = (int)sy;
            if (ix < 0 || iy < 0 || ix >= W || iy >= H) continue;
            int idx = iy * W + ix;
            if (!thru[idx] || depth[idx] < d) continue;
            double fade = 1.0 - Sat((d - 9) / 53.0);
            if (fade < 0.12) continue;
            double c = rng.NextDouble();
            int r, g, b;
            if (c < 0.45) { r = 255; g = 176; b = 66; } else if (c < 0.8) { r = 246; g = 122; b = 40; } else { r = 210; g = 92; b = 34; }
            int sz = d < 22 ? 2 : 1;
            for (int dy2 = 0; dy2 < sz; dy2++) for (int dx2 = 0; dx2 < sz; dx2++)
                    PutA(ix + dx2, iy + dy2, r, g, b, 0.5 * fade + 0.25);
        }
    }

    // ---------------- text ----------------
    static Dictionary<char, string[]> font;
    static void BuildFont()
    {
        font = new Dictionary<char, string[]>();
        font['I'] = new string[] { "###", ".#.", ".#.", ".#.", ".#.", ".#.", "###" };
        font['m'] = new string[] { ".....", ".....", "##.#.", "#.#.#", "#.#.#", "#.#.#", "#.#.#" };
        font['e'] = new string[] { ".....", ".....", ".###.", "#...#", "#####", "#....", ".###." };
        font['r'] = new string[] { ".....", ".....", "#.##.", "##..#", "#....", "#....", "#...." };
        font['s'] = new string[] { ".....", ".....", ".####", "#....", ".###.", "....#", "####." };
        font['i'] = new string[] { "#", ".", "#", "#", "#", "#", "#" };
        font['v'] = new string[] { ".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#.." };
        font['P'] = new string[] { "####.", "#...#", "#...#", "####.", "#....", "#....", "#...." };
        font['o'] = new string[] { ".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###." };
        font['t'] = new string[] { ".#.", ".#.", "###", ".#.", ".#.", ".#.", "..#" };
        font['a'] = new string[] { ".....", ".....", ".###.", "....#", ".####", "#...#", ".####" };
        font['l'] = new string[] { "#.", "#.", "#.", "#.", "#.", "#.", "##" };
        font[' '] = new string[] { "...", "...", "...", "...", "...", "...", "..." };
    }
    static int TextW(string s, int sc)
    {
        int w = 0;
        foreach (char ch in s) w += font[ch][0].Length + 1;
        return (w - 1) * sc;
    }
    static void DrawTextC(string s, int x, int y, int sc, int r, int g, int b, double a)
    {
        int cx = x;
        foreach (char ch in s)
        {
            string[] rows = font[ch];
            int wch = rows[0].Length;
            for (int ry = 0; ry < rows.Length; ry++) for (int rx = 0; rx < wch; rx++)
                    if (rows[ry][rx] == '#')
                        for (int sy = 0; sy < sc; sy++) for (int sxp = 0; sxp < sc; sxp++)
                                PutA(cx + rx * sc + sxp, y + ry * sc + sy, r, g, b, a);
            cx += (wch + 1) * sc;
        }
    }

    static void Save(string path)
    {
        Bitmap bmp = new Bitmap(W, H, PixelFormat.Format24bppRgb);
        BitmapData bd = bmp.LockBits(new Rectangle(0, 0, W, H), ImageLockMode.WriteOnly, PixelFormat.Format24bppRgb);
        byte[] row = new byte[bd.Stride];
        for (int y = 0; y < H; y++)
        {
            for (int x = 0; x < W; x++)
            {
                int c = fb[y * W + x];
                row[x * 3 + 0] = (byte)(c & 255);
                row[x * 3 + 1] = (byte)((c >> 8) & 255);
                row[x * 3 + 2] = (byte)((c >> 16) & 255);
            }
            Marshal.Copy(row, 0, (IntPtr)(bd.Scan0.ToInt64() + (long)y * bd.Stride), bd.Stride);
        }
        bmp.UnlockBits(bd);
        bmp.Save(path, ImageFormat.Png);
        bmp.Dispose();
    }

    public static void Render(string path)
    {
        BuildFont();
        BuildTextures();
        BuildOverworld();
        BuildNether();
        ComputeLight(ow, true);
        ComputeLight(nw, false);
        SetupCamera();
        RenderScene();
        DrawSun();
        DrawParticles();
        string s = "Immersive Portal";
        int sc = 8;
        int tw = TextW(s, sc);
        int tx = (W - tw) / 2, ty = H - 7 * sc - 62;
        DrawTextC(s, tx + sc, ty + sc, sc, 22, 22, 24, 0.62);
        DrawTextC(s, tx, ty, sc, 255, 255, 255, 1.0);
        Save(path);
    }
}
