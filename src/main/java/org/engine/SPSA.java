package org.engine;

/*
NMPBase, int, 2.0, 0.0, 10.0, 0.5, 0.005
NMPDepthScale100, int, 23.0, 0.0, 200.0, 2.0, 0.005
NMPEvalMargin, int, 241.0, 1.0, 4000.0, 50.0, 0.003
NMPEvalMax, int, 4.0, 0.0, 10.0, 0.5, 0.005
LMRBase100, int, 77.0, 0.0, 300.0, 5.0, 0.003
LMRDivisor100, int, 216.0, 1.0, 1000.0, 10.0, 0.003
ReverseFUTMaxDepth, int, 3.0, 0.0, 8.0, 0.3, 0.00333333
ReverseFUTMarginPerDepth, int, 111.0, 0.0, 1024.0, 16.0, 0.003
FUTMaxDepth, int, 2.0, 0.0, 8.0, 0.3, 0.00333333
FUTMarginPerDepth, int, 110.0, 0.0, 1024.0, 16.0, 0.003
QSeeMargin, int, -7.0, -1024.0, 1024.0, 8.0, 0.003
LMPMaxDepth, int, 3.0, 0.0, 8.0, 0.3, 0.00333333
LMPBaseThreshold, int, 4.0, 0.0, 64.0, 1.0, 0.00333333
LMPPerDepth, int, 1.0, 0.0, 16.0, 1.0, 0.00333333
LMPMarginPerDepth, int, 129.0, 0.0, 1024.0, 16.0, 0.003
IIRMinPVDepth, int, 2.0, 0.0, 16.0, 1.0, 0.005
IIRMinCutDepth, int, 3.0, 0.0, 16.0, 1.0, 0.005
SEMinDepth, int, 8.0, 4.0, 16.0, 1.0, 0.005
SEMarginPerDepth, int, 3.0, 1.0, 8.0, 0.5, 0.005
SEDoubleMargin, int, 24.0, 8.0, 64.0, 4.0, 0.005
SETripleMargin, int, 80.0, 32.0, 128.0, 8.0, 0.005

Notes:
- LMRBase100 -> lmrBase = value / 100.0
- LMRDivisor100 -> lmrDivisor = value / 100.0
- SEMinDepth: Minimum depth to trigger singular extension
- SEMarginPerDepth: Margin per depth for singular verification search
- SEDoubleMargin: Additional margin threshold for double extension
- SETripleMargin: Additional margin threshold for triple extension
*/

public final class SPSA {    
    public double lmrBase = 0.77;
    public double lmrDivisor = 2.16;
    public int reverseFutilityMaxDepth = 3;
    public int reverseFutilityMarginPerDepth = 111;
    public int futilityMaxDepth = 2;
    public int futilityMarginPerDepth = 110;
    public int lmpMarginPerDepth = 129;
    public int qseeMargin = -7;
    public int nmpBase = 2;
    public double nmpDepthScale = 0.23;
    public int nmpEvalMargin = 241;
    public int nmpEvalMax = 4;
    public int lmpMaxDepth = 3;
    public int lmpBaseThreshold = 4;
    public int lmpPerDepth = 1;
    public int iirMinPVDepth = 2;
    public int iirMinCutDepth = 3;
    public int seMinDepth = 8;
    public int seMarginPerDepth = 3;
    public int seDoubleMargin = 24;
    public int seTripleMargin = 80;

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
            case "ReverseFUTMaxDepth":
                reverseFutilityMaxDepth = Math.max(0, value);
                break;
            case "ReverseFUTMarginPerDepth":
                reverseFutilityMarginPerDepth = Math.max(0, value);
                break;
            case "FUTMaxDepth":
                futilityMaxDepth = Math.max(0, value);
                break;
            case "FUTMarginPerDepth":
                futilityMarginPerDepth = Math.max(0, value);
                break;
            case "QSeeMargin":
                qseeMargin = value;
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
            case "LMPMarginPerDepth":
                lmpMarginPerDepth = Math.max(0, value);
                break;
            case "LMPMaxDepth":
                lmpMaxDepth = Math.max(0, value);
                break;
            case "LMPBaseThreshold":
                lmpBaseThreshold = Math.max(0, value);
                break;
            case "LMPPerDepth":
                lmpPerDepth = Math.max(0, value);
                break;
            case "IIRMinPVDepth":
                iirMinPVDepth = Math.max(0, value);
                break;
            case "IIRMinCutDepth":
                iirMinCutDepth = Math.max(0, value);
                break;
            case "SEMinDepth":
                seMinDepth = Math.max(0, value);
                break;
            case "SEMarginPerDepth":
                seMarginPerDepth = Math.max(1, value);
                break;
            case "SEDoubleMargin":
                seDoubleMargin = Math.max(1, value);
                break;
            case "SETripleMargin":
                seTripleMargin = Math.max(1, value);
                break;
            default:
                break;
        }
    }
}


