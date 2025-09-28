package org.engine;

/*
OpenBench SPSA params (paste into your OpenBench SPSA config)

All parameters below correspond 1:1 to UCI options exposed by this engine.
Values with a 100 suffix are scaled by 100 (engine divides by 100.0).

NMPBase, int, 2.0, 0.0, 10.0, 0.5, 0.005
NMPDepthScale100, int, 23.0, 0.0, 200.0, 2.0, 0.005
NMPEvalMargin, int, 217.0, 1.0, 4000.0, 50.0, 0.003
NMPEvalMax, int, 4.0, 0.0, 10.0, 0.5, 0.005
LMRBase100, int, 79.0, 0.0, 300.0, 5.0, 0.003
LMRDivisor100, int, 215.0, 1.0, 1000.0, 10.0, 0.003
FUTMaxDepth, int, 3.0, 0.0, 8.0, 0.3, 0.00333333
FUTMarginPerDepth, int, 124.0, 0.0, 1024.0, 16.0, 0.003
QSeeMargin, int, -9.0, -1024.0, 1024.0, 8.0, 0.003

Notes:
- LMRBase100 -> lmrBase = value / 100.0
- LMRDivisor100 -> lmrDivisor = value / 100.0
- Apply changes by sending ucinewgame (Search is recreated capturing new finals)
*/

public final class SPSA {    
    public double lmrBase = 0.79;
    public double lmrDivisor = 2.15;
    public int futilityMaxDepth = 3;
    public int futilityMarginPerDepth = 124;
    public int qseeMargin = -9;
    public int nmpBase = 2;
    public double nmpDepthScale = 0.23;
    public int nmpEvalMargin = 217;
    public int nmpEvalMax = 4;

    public void setByName(String name, int value) {
        if (name == null) return;
        String key = name.trim();
        switch (key) {
            case "LMRBase100":
                lmrBase = Math.max(0.0, value / 100.0);
                break;
            case "LMRDivisor100":
                lmrDivisor = Math.max(0.01, value / 100.0);
                break;
            case "FUTMaxDepth":
                futilityMaxDepth = Math.max(0, value);
                break;
            case "FUTMarginPerDepth":
                futilityMarginPerDepth = Math.max(0, value);
                break;
            case "QSeeMargin":
                qseeMargin = value; // allow negative margins
                break;
            case "NMPBase":
                nmpBase = Math.max(0, value);
                break;
            case "NMPDepthScale100":
                nmpDepthScale = Math.max(0.0, value / 100.0);
                break;
            case "NMPEvalMargin":
                nmpEvalMargin = Math.max(1, value);
                break;
            case "NMPEvalMax":
                nmpEvalMax = Math.max(0, value);
                break;
            default:
                break;
        }
    }
}


