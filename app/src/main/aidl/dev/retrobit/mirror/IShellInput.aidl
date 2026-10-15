package dev.retrobit.mirror;

interface IShellInput {
    void destroy() = 16777114;
    boolean pointer(int action, int pointerId, float x, float y) = 1;
    boolean key(int action, int keyCode, int meta, int source) = 2;
    boolean text(String value) = 3;
    String ping() = 4;
}
