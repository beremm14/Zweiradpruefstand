package measure;

import data.Bike;
import data.Database;
import data.Config;
import data.Datapoint;
import data.Environment;
import data.PreDatapoint;
import data.RawDatapoint;
import java.util.LinkedList;
import java.util.List;
import logging.Logger;

/**
 *
 * @author emil
 */
public class Calculate {

    private static final Logger LOG = Logger.getLogger(Calculate.class.getName());

    private final Bike bike = Bike.getInstance();
    private final Config config = Config.getInstance();
    private final Database data = Database.getInstance();
    private final Environment environment = Environment.getInstance();

    private void filterData(List<RawDatapoint> rawList) {
        EWMA filter = new EWMA(rawList);
        filter.compute(config.getOrder(), config.getSmoothing());
        data.setFilteredPreList(filter.getFilteredPreList());
        data.setFilteredRawList(filter.getFilteredRawList());

        if (config.isPoly()) {
            PolynomialRegression regression = new PolynomialRegression(data.getRawList(), 6);
            data.setFilteredRawList(regression.filterRawData());

            for (RawDatapoint rdp : data.getFilteredList()) {
                data.addFilterPDP(calcOneRpm(rdp));
            }
        } else if (config.isAverage()) {
            MovingAverage moving = new MovingAverage(3, data.getRawList());
            data.setFilteredRawList(moving.compute());

            for (RawDatapoint rdp : data.getFilteredList()) {
                data.addFilterPDP(calcOneRpm(rdp));
            }
        }
    }

    //Calculates One Point
    public PreDatapoint calcOneRpm(RawDatapoint rdp) {
        double engTime = (double) rdp.getEngTime();
        double wheelTime = (double) rdp.getWheelTime();

        double totalImpulse = 26.0;
        double engRpm;
        double wheelRpm;

        wheelRpm = 60000000.0 / (totalImpulse * wheelTime);
        if (Double.isInfinite(wheelRpm)) {
            wheelRpm = 0;
        }

        if (Bike.getInstance().isTwoStroke()) {
            engRpm = 60000000.0 / engTime;
        } else {
            engRpm = 120000000.0 / engTime;
        }
        if (Double.isInfinite(engRpm)) {
            engRpm = 0;
        }

        return new PreDatapoint(engRpm, wheelRpm, (double) rdp.getTime());
    }

    public PreDatapoint calcWheelOnly(RawDatapoint rdp) {
        /*double totalImpulse = 26.0;
        double wheelCount = (double) rdp.getWheelTime();
        double time = (double) rdp.getTime(); //µs

        double wheelRpm = (wheelCount / (((double) time) * totalImpulse)) * 60000000.0;

        return new PreDatapoint(wheelRpm, (double) rdp.getTime());*/
        throw new UnsupportedOperationException();
    }

    //Calculates One Point
    public double calcMps(PreDatapoint pdp) {
        return 0.35 * Math.PI * (pdp.getWheelRpm() / 60.0);
    }

    public double calcKmh(PreDatapoint pdp) {
        return calcMps(pdp) * 3.6;
    }

    public double calcMih(PreDatapoint pdp) {
        return calcMps(pdp) * 2.2369362920544;
    }

    public List<PreDatapoint> calcPreList(List<RawDatapoint> rawList) {
        List<PreDatapoint> preList = new LinkedList<>();
        for (RawDatapoint rdp : rawList) {
            preList.add(calcOneRpm(rdp));
        }
        return preList;
    }

    public void calcPower(List<RawDatapoint> rawList, List<PreDatapoint> preList, boolean filter, boolean rmData) {
        if (filter) {
            filterData(rawList);
        }

        if (rawList != data.getRawList()) {
            data.setRawList(rawList);
        }

        if (preList != data.getPreList()) {
            data.setPreList(preList);
        }
        if (rmData) {
            try {
                data.rmFirstPDP(5);
            } catch (Exception ex) {
                LOG.warning(ex);
            }
        }

        //Calculation without Schlepp-Power
        if (bike.isStartStopMethod()) {

            double lastOmega = 0;
            double lastTime = 0;

            for (PreDatapoint pdp : data.getPreOrFilteredList()) {
                double omega = (2 * Math.PI / 60) * pdp.getWheelRpm();
                double dOmega = omega - lastOmega;
                double alpha = dOmega / ((pdp.getTime() - lastTime) / 1000000.0);
                double wheelPower = omega * alpha * config.getInertia() * config.getPowerCorr();
                double engOmega = (2. * Math.PI / 60.) * pdp.getEngRpm();

                if (wheelPower > 0 && dOmega > 0) {
                    Datapoint dp = new Datapoint(wheelPower, engOmega);
                    data.addDP(dp);
                    if (pdp.getEngRpm() > config.getStartRpm() && pdp.getEngRpm() < config.getStopRpm() && dp.getPower() > 0) {
                        data.addXYValues(dp, pdp);
                    }
                    lastOmega = omega;
                }
                lastTime = pdp.getTime();
            }

            //Calculation within Schlepp-Power
        } else {

            double lastOmega = 0;
            double lastSchleppOmega = 0;

            for (PreDatapoint pdp : data.getPreOrFilteredList()) {
                double omega = (2.0 * Math.PI / 60.0) * pdp.getWheelRpm();
                double dOmega = omega - lastOmega;
                double alpha = dOmega / (pdp.getTime() / 1000000.0);
                double wheelPower = omega * alpha * config.getInertia();
                
                double schleppPower = 0;
                for (PreDatapoint schleppPDP : data.getSchleppPreList()) {
                    double schleppOmega = (2 * Math.PI / 60) * schleppPDP.getWheelRpm();
                    if (schleppOmega < omega) {
                        double schleppDOmega = schleppOmega - lastSchleppOmega;
                        double schleppAlpha = schleppDOmega / schleppPDP.getTime();
                        schleppPower = schleppOmega * schleppAlpha * config.getInertia();
                        break;
                    }
                    lastSchleppOmega = schleppOmega;
                }

                Datapoint dp = new Datapoint(wheelPower, schleppPower, omega);
                data.addDP(dp);
                data.addXYValues(dp, pdp);

                lastOmega = omega;
            }
        }

        //Evaluation of Maximum-Values
        data.rmFirstDP(1);
        evaluateMaxValues();
        
    }
    
    public void evaluateMaxValues() {
        //Torque and Power
        double maxTorque = data.getDataList().get(0).getTorque();
        double maxPower = data.getDataList().get(0).getPower();
        for (Datapoint dp : data.getDataList()) {
            if (dp.getTorque() > maxTorque) {
                maxTorque = dp.getTorque();
            }
            if (dp.getPower() > maxPower) {
                maxPower = dp.getPower();
            }
        }
        data.setBikePower(maxPower);
        data.setBikeTorque(maxTorque);

        //Velocity
        double maxVelocity = calcMps(data.getPreOrFilteredList().get(0));
        for (PreDatapoint pdp : data.getPreOrFilteredList()) {
            if (calcMps(pdp) > maxVelocity) {
                maxVelocity = calcMps(pdp);
            }
        }
        switch (config.getVelocity()) {
            case MPS:
                data.setBikeVelo(maxVelocity);
                break;
            case MIH:
                data.setBikeVelo(maxVelocity * 2.2369362920544);
                break;
            case KMH:
                data.setBikeVelo(maxVelocity * 3.6);
                break;
        }

        if (bike.isMeasTemp()) {
            double maxEngTemp = data.getEngTempList().get(0);
            for (Double engTemp : data.getEngTempList()) {
                if (engTemp > maxEngTemp) {
                    maxEngTemp = engTemp;
                }
            }

            double maxFumeTemp = data.getFumeTempList().get(0);
            for (Double fumeTemp : data.getFumeTempList()) {
                if (fumeTemp > maxFumeTemp) {
                    maxFumeTemp = fumeTemp;
                }
            }

            environment.setEngTemp(maxEngTemp);
            environment.setFumeTemp(maxFumeTemp);
        }
    }
}
