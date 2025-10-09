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
SingularMinDepth, int, 7.0, 0.0, 32.0, 1.0, 0.005
SingularMarginPerDepth, int, 2.0, 0.0, 8.0, 1.0, 0.005
TMHeuristicsMinDepth, int, 6.0, 0.0, 32.0, 1.0, 0.005
TMMaxExtensionFactor100, int, 350.0, 100.0, 1000.0, 10.0, 0.003
TMInstabilityScoreWeight10000, int, 70.0, 0.0, 1000.0, 5.0, 0.003
SEEPruningMaxDepth, int, 8.0, 0.0, 16.0, 1.0, 0.005
SEEPruningQuietMargin, int, -60.0, -512.0, 0.0, 10.0, 0.003
SEEPruningCaptureMargin, int, -20.0, -512.0, 0.0, 10.0, 0.003

Notes:
- LMRBase100 -> lmrBase = value / 100.0
- LMRDivisor100 -> lmrDivisor = value / 100.0
- TMMaxExtensionFactor100 -> tmMaxExtensionFactor = value / 100.0
- TMInstabilityScoreWeight10000 -> tmInstabilityScoreWeight = value / 10000.0
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
    public int singularMinDepth = 7;
    public int singularMarginPerDepth = 2;
    public int tmHeuristicsMinDepth = 6;
    public double tmMaxExtensionFactor = 3.5;
    public double tmInstabilityScoreWeight = 0.007;
    public int seePruningMaxDepth = 8;
    public int seePruningQuietMargin = -60;
    public int seePruningCaptureMargin = -20;

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
            case "SingularMinDepth":
                singularMinDepth = Math.max(0, value);
                break;
            case "SingularMarginPerDepth":
                singularMarginPerDepth = Math.max(0, value);
                break;
            case "TMHeuristicsMinDepth":
                tmHeuristicsMinDepth = Math.max(0, value);
                break;
            case "TMMaxExtensionFactor100":
                tmMaxExtensionFactor = Math.max(0.0, value / 100.0);
                break;
            case "TMInstabilityScoreWeight10000":
                tmInstabilityScoreWeight = Math.max(0.0, value / 10000.0);
                break;
            case "SEEPruningMaxDepth":
                seePruningMaxDepth = Math.max(0, value);
                break;
            case "SEEPruningQuietMargin":
                seePruningQuietMargin = value;
                break;
            case "SEEPruningCaptureMargin":
                seePruningCaptureMargin = value;
                break;
            default:
                break;
        }
    }
}


