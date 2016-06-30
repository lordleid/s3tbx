package org.esa.s3tbx.olci.radiometry.rayleighcorrection;

import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.esa.s3tbx.olci.radiometry.smilecorr.SmileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * @author muhammad.bc.
 */
public class RayleighCorrAlgorithm {

    public static final double STD_SEA_LEVEL_PRESSURE = 1013.0;
    private RayleighCorrectionAux rayleighCorrectionAux;
    private double[] tPoly;
    private double[] thetas;
    private double[] rayAlbedoLuts;
    private double[][][] rayCooefMatrixA;
    private double[][][] rayCooefMatrixB;
    private double[][][] rayCooefMatrixC;
    private double[][][] rayCooefMatrixD;

    public RayleighCorrAlgorithm() {
        rayleighCorrectionAux = new RayleighCorrectionAux();
        try {
            Path resolve = rayleighCorrectionAux.installAuxdata().resolve("matrix.txt");

            JSONParser jsonObject = new JSONParser();
            JSONObject parse = (JSONObject) jsonObject.parse(new FileReader(resolve.toString()));

            tPoly = rayleighCorrectionAux.parseJSON1DimArray(parse, "tau_ray");
            thetas = rayleighCorrectionAux.parseJSON1DimArray(parse, "theta");
            rayAlbedoLuts = rayleighCorrectionAux.parseJSON1DimArray(parse, "ray_albedo_lut");
            ArrayList<double[][][]> ray_coeff_matrix = rayleighCorrectionAux.parseJSON3DimArray(parse, "ray_coeff_matrix");

            rayCooefMatrixA = ray_coeff_matrix.get(0);
            rayCooefMatrixB = ray_coeff_matrix.get(1);
            rayCooefMatrixC = ray_coeff_matrix.get(2);
            rayCooefMatrixD = ray_coeff_matrix.get(3);


        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    //Copied from org.esa.beam.meris.case2.MerisCase2BasisWaterOp
    protected double getAzimuthDifference(double viewAzimuthAngle, double sunAzimuthAngle) {
        double azi_diff_deg = Math.abs(viewAzimuthAngle - sunAzimuthAngle); /* azimuth difference */
              /* reverse azi difference */
        if (azi_diff_deg > 180.0) {
            azi_diff_deg = 360.0 - azi_diff_deg;
        }
//        azi_diff_deg = 180.0 - azi_diff_deg; /* different definitions in MERIS data and MC /HL simulation */
        return azi_diff_deg;
    }

    public double[] getTaurStd(double[] lam) {
        final double[] taurs = new double[lam.length];
        for (int i = 0; i < lam.length; i++) {
            taurs[i] = Math.exp(-4.637) * Math.pow((lam[i] / 1000), -4.0679);
        }
        return taurs;
    }

    public double[] getPressureAtSurface(double[] seaLevelPressure, double[] height) {
        double pressureAtms[] = new double[seaLevelPressure.length];
        for (int i = 0; i < seaLevelPressure.length; i++) {
            pressureAtms[i] = seaLevelPressure[i] * Math.exp(-height[i] / 8000);
        }
        return pressureAtms;
    }

    public double getPressureAtSurface(double seaLevelPressure, double height) {
        return seaLevelPressure * Math.exp(-height / 8000);
    }

    public double[] getRayleighOpticalThickness(double[] pressureAtms, double taur_std) {
        final double thickness[] = new double[pressureAtms.length];
        double pressureFactor = taur_std / STD_SEA_LEVEL_PRESSURE;
        for (int i = 0; i < pressureAtms.length; i++) {
            thickness[i] = pressureAtms[i] * pressureFactor;
        }
        return thickness;
    }

    public double getRayleighOpticalThickness(double pressureAtms, double taur_std) {
        return pressureAtms * taur_std / STD_SEA_LEVEL_PRESSURE;
    }

    protected double phaseRaylMin(double sunZenithAngle, double viewZenithAngle, double azimuthDifference) {
        double cosScatterAngle = cosScatterAngle(sunZenithAngle, viewZenithAngle, azimuthDifference);
        return 0.75 * (1.0 + cosScatterAngle * cosScatterAngle);
    }

    protected double cosScatterAngle(double sunZenithAngle, double viewZenithAngle, double azimuthDifferent) {
        final double cos_view = Math.cos(viewZenithAngle);
        final double cos_sun = Math.cos(sunZenithAngle);

        final double sin_view = Math.sin(viewZenithAngle);
        final double sin_sun = Math.sin(sunZenithAngle);
        final double cos_azi_dif = Math.cos(azimuthDifferent);
        return (-cos_view * cos_sun) - (sin_view * sin_sun * cos_azi_dif);
    }

    public double[] getRayleighReflectance(double[] taurPoZ, double[] sunZenithAngles, double[] sunAzimuthAngles, double[] viewZenithAngles, double[] viewAzimuthAngles) {

        final double reflRaly[] = new double[viewZenithAngles.length];

        double[] sunZenithAngleRad = SmileUtils.convertDegreesToRadians(sunZenithAngles);
        double[] viewZenithAngleRad = SmileUtils.convertDegreesToRadians(viewZenithAngles);

        for (int i = 0; i < viewZenithAngles.length; i++) {
            final double azimuthDifferenceRad = Math.toRadians(getAzimuthDifference(viewAzimuthAngles[i], sunAzimuthAngles[i]));
            final double sunZenithAngle = sunZenithAngleRad[i];
            final double viewZenithAngle = viewZenithAngleRad[i];

            final double phaseRaylMin = phaseRaylMin(sunZenithAngle, viewZenithAngle, azimuthDifferenceRad);
            final double cos_sunZenith = Math.cos(sunZenithAngle);
            final double cos_viewZenith = Math.cos(viewZenithAngle);
            reflRaly[i] = cos_sunZenith * taurPoZ[i] * phaseRaylMin / (4 * Math.PI) * (1 / cos_viewZenith) * Math.PI;

        }
        return reflRaly;
    }

    public double[] getPhaseRaylMin(double[] sunZenithAngleRads, double[] sunAzimuthAngles, double[] viewZenithAngleRads, double[] viewAzimuthAngles) {

        double phaseRaylMin[] = new double[sunAzimuthAngles.length];

        for (int i = 0; i < sunAzimuthAngles.length; i++) {
            double azimuthDifferenceRad = Math.toRadians(getAzimuthDifference(viewAzimuthAngles[i], sunAzimuthAngles[i]));
            phaseRaylMin[i] = phaseRaylMin(sunZenithAngleRads[i], viewZenithAngleRads[i], azimuthDifferenceRad);
        }
        return phaseRaylMin;
    }


    /**
     * @param sourceWL
     * @return sigma
     */
    public double[] getCrossSectionSigma(double[] sourceWL) {
        double n_ratio = 1 + 0.54 * (RayleighConstants.CO2 - 0.0003);
        double molecularDen = Math.pow(RayleighConstants.Molecular_cm3, 2);
        double[] sigma = new double[sourceWL.length];

        for (int i = 0; i < sourceWL.length; i++) {

            double sourceWLmm = sourceWL[i] / 1000f;
            double sourceWLcm = sourceWLmm / 10000f;

            double F_N2 = 1.034 + 0.000317 / Math.pow(sourceWLmm, 2);
            double F_O2 = 1.096 + 0.001385 / Math.pow(sourceWLmm, 2) + 0.0001448 / Math.pow(sourceWLmm, 4);

            double F_air = (78.084 * F_N2 + 20.946 * F_O2 + 0.934 * (1 + RayleighConstants.C_CO2) * 1.15) / (78.084 + 20.946 + 0.934 + RayleighConstants.C_CO2);
            double n_1_300 = (8060.51 + (2480990. / (132.274 - Math.pow(sourceWLmm, -2))) + (17455.7 / (39.32957 - Math.pow(sourceWLmm, -2)))) / 100000000.0;
            double nCO2 = n_ratio * (1 + n_1_300);
            sigma[i] = 24 * Math.pow(Math.PI, 3) * Math.pow((Math.pow(nCO2, 2) - 1), 2) / (Math.pow(sourceWLcm, 4) * molecularDen * Math.pow((Math.pow(nCO2, 2) + 2), 2)) * F_air;
        }
        return sigma;
    }

    public double[] getRayleighOpticalThicknessII(double seaLevelPressure[], double altitude[], double latitude[], double sigma[]) {

        double rayeighOpticalThickness[] = new double[altitude.length];
        for (int i = 0; i < altitude.length; i++) {

            double airPressurePixelcm2 = seaLevelPressure[i] * Math.pow((1. - 0.0065 * altitude[i] / 288.15), 5.255) * 1000;
            double latRad = Math.toRadians(latitude[i]);
            double cos2LatRad = Math.cos(2 * latRad);
            double g0 = RayleighConstants.ACCELERATION_GRAVITY_SEA_LEVEL_458_LATITUDE * (1 - 0.0026373 * cos2LatRad + 0.0000059 * Math.pow(cos2LatRad, 2));
            double effectiveMassWeightAltitude = 0.73737 * altitude[i] + 5517.56;

            double g = g0 - (0.0003085462 + 0.000000227 * cos2LatRad) * effectiveMassWeightAltitude +
                    (0.00000000007254 + 0.0000000000001 * cos2LatRad) * Math.pow(effectiveMassWeightAltitude, 2) -
                    (1.517E-17 + 6E-20 * cos2LatRad) * Math.pow(effectiveMassWeightAltitude, 3);

            double factor = (airPressurePixelcm2 * RayleighConstants.AVOGADRO_NUMBER) / (RayleighConstants.MEAN_MOLECULAR_WEIGHT_C02 * g);

            rayeighOpticalThickness[i] = factor * sigma[i];
        }
        return rayeighOpticalThickness;
    }

    public HashMap<String, double[]> getRHO_BRR(double[] szaRads, double[] ozaRads, double[] saaRads, double[] aooRads, double[] taur, double[] reflectance, double[] lineSpace) {

        HashMap<String, double[]> rayleighHashMap = new HashMap<>();

        double[] rho_BRR = new double[ozaRads.length];
        double[] sphericalFactor = new double[ozaRads.length];
        double[] rho_toaR = new double[ozaRads.length];
        double[] tR_thetaV = new double[ozaRads.length];
        double[] tR_thetaS = new double[ozaRads.length];

        rayleighHashMap.put("rBRR", rho_BRR);
        rayleighHashMap.put("sphericalAlbedoFactor", sphericalFactor);
        rayleighHashMap.put("rtoaRay", rho_toaR);
        rayleighHashMap.put("transVRay", tR_thetaV);
        rayleighHashMap.put("transSRay", tR_thetaS);

        /*
        rhorF3Band.writePixels(0, y, width, 1, rho_Rm[2, i])
        sARayBand = raycorProduct.getBand("sARay_" + str(i + 1))
        sARayBand.writePixels(0, y, width, 1, sARay[i])
        */


        double[] fourierSeries = new double[3];
        double rho_Rm[] = new double[3];
        double sARay = 1;

        double a[] = new double[3];
        double b[] = new double[3];
        double c[] = new double[3];
        double d[] = new double[3];
        Arrays.fill(a, 1);
        Arrays.fill(b, 1);
        Arrays.fill(c, 1);
        Arrays.fill(d, 1);

        for (int index = 0; index < ozaRads.length; index++) {
            // Fourier components of multiple scattering
            double sinSZARad = Math.sin(szaRads[index]);
            double cosSZARad = Math.cos(szaRads[index]);
            double cosOZARad = Math.cos(ozaRads[index]);
            double sinOZARad = Math.sin(ozaRads[index]);

            double sinSZA2 = Math.pow(sinSZARad, 2);
            double sinOZA2 = Math.pow(sinOZARad, 2);

            double taurVal = taur[index];
            if (Double.isNaN(taurVal)) {
                rho_BRR[index] = taurVal;
                continue;
            }

            //Rayleigh Phase function, 3 Fourier terms
            fourierSeries[0] = (float) (3 * RayleighConstants.PA /
                    4 * (1 + Math.pow(cosSZARad, 2) * Math.pow(cosOZARad, 2) + (sinSZA2 * sinOZA2) / 2) + RayleighConstants.PB);
            fourierSeries[1] = (float) (-3 * RayleighConstants.PA / 4 * cosSZARad * cosOZARad * sinSZARad * sinOZARad);
            fourierSeries[2] = (float) (3 * RayleighConstants.PA / 16 * sinSZA2 * sinOZA2);

            double cosDelta = Math.cos(aooRads[index] - saaRads[index]);
            double aziDiff = Math.acos(cosDelta); // in radian
            double massAir = (1 / Math.cos(szaRads[index]) + 1 / Math.cos(ozaRads[index]));

//            BivariateGridInterpolator gridInterpolator = new BicubicSplineInterpolator();
//            double yVal = ozaRads[index];
//            double xVal = szaRads[index];
//            for (int i = 0; i < a.length; i++) {
//                BivariateFunction interpolate = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixA[i]);
//                a[i] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixA[i]).value(xVal, yVal);
//                b[i] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixB[i]).value(xVal, yVal);
//                c[i] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixC[i]).value(xVal, yVal);
//                d[i] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixD[i]).value(xVal, yVal);
//            }
            // Rayleigh primary scattering

            for (int i = 0; i < fourierSeries.length; i++) {
                double rayPrimaryScatters = fourierSeries[i] / (4.0 * (cosSZARad + cosOZARad)) * (1. - Math.exp(-massAir * taurVal));
                double v = a[i] + b[i] * taurVal + c[i] * Math.pow(taurVal, 2) + d[i] * Math.pow(taurVal, 3);
                rho_Rm[i] = v * rayPrimaryScatters;
            }
            //sum of the fourier
            double rho_R = rho_Rm[0] + 2.0 * rho_Rm[1] * Math.cos(aziDiff) + 2. * rho_Rm[2] * Math.cos(2. * aziDiff);
            // polynomial coefficients tpoly0, tpoly1 and tpoly2 from MERIS LUT

            double tRs = ((2. / 3. + cosSZARad) + (2. / 3. - cosSZARad) * Math.exp(-taur[index] / cosSZARad)) / (4. / 3. + taur[index]);

            tR_thetaS[index] = tPoly[0] + tPoly[1] * tRs + tPoly[2] * Math.pow(tRs, 2);
            //#Rayleigh Transmittance sun - surface
            double tRv = ((2. / 3. + cosOZARad) + (2. / 3. - cosOZARad) * Math.exp(-taur[index] / cosOZARad)) / (4. / 3. + taur[index]);
            //#Rayleigh Transmittance surface - sensor
            tR_thetaV[index] = tPoly[0] + tPoly[1] * tRv + tPoly[2] * Math.pow(tRv, 2);

            LinearInterpolator interpolator = new LinearInterpolator();
            PolynomialSplineFunction interpolate = interpolator.interpolate(lineSpace, rayAlbedoLuts);
//            ARay[(i, lineSpace)] = rayAlb_f(taur[(i, x)])  # Rayleigh spherical albedo
//            taurTab = np.linspace(0.0, 1.0, num=dimTaur)
//            rayAlb_f = interp1d(taurTab, rayADF['ray_albedo_lut'])
//            sARay = np.zeros((nbands, width), dtype=np.float32)


            rho_toaR[index] = (reflectance[index] - rho_R) / (tR_thetaS[index] * tR_thetaV[index]); //toa reflectance corrected for Rayleigh scattering

            sphericalFactor[index] = 1.0 / (1.0 + sARay * rho_toaR[index]); //#factor used in the next equation to account for the spherical albedo
            //#top of aerosol reflectance, which is equal to bottom of Rayleigh reflectance
            rho_BRR[index] = (float) (rho_toaR[index] * sphericalFactor[index]);
        }

        return rayleighHashMap;
    }


    public HashMap<String, double[]> correct(double[] reflectances, double[] seaAirPressure, double[] altitude, double[] szaRads, double[] ozaRads, double[] ray_phase_min) {
        double[] rRaySimple = new double[ozaRads.length];
        double[] taurS = new double[ozaRads.length];
        HashMap<String, double[]> rayHashMap = new HashMap<>();
        rayHashMap.put("taurS",taurS);
        rayHashMap.put("rRaySample",rRaySimple);

        for (int i = 0; i < ray_phase_min.length; i++) {
            double taurSLocal = Math.exp(-4.637) * Math.pow((reflectances[i] / 1000.0), -4.0679);
            double pressureAtms = seaAirPressure[i] * Math.exp(-altitude[i] / 8000.0);
            double pressureFactor = taurSLocal / 1013.0;
            taurS[i] = pressureAtms * pressureFactor;
            rRaySimple[i] = (float) (Math.cos(szaRads[i]) * taurS[i] * ray_phase_min[i] / (4 * 3.1415926) * (1 / Math.cos(ozaRads[i]) * 3.1415926));
        }

        return rayHashMap;
    }


    public double[] getLineSpace(double start, double end, int interval) {
        if (interval < 0) {
            throw new NegativeArraySizeException("Array cant have negative index");
        }
        double[] temp = new double[interval];
        double steps = (end - start) / (interval - 1);
        for (int i = 0; i < temp.length; i++) {
            temp[i] = steps * i;
        }
        return temp;
    }
}
