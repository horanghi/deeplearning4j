/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.spark.impl.multilayer;

import org.apache.spark.Accumulator;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.spark.impl.common.BestScoreIterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Iterative reduce with
 * flat map using map partitions
 *
 * @author Adam Gibson
 */


public class IterativeReduceFlatMap implements FlatMapFunction<Iterator<DataSet>,Tuple3<INDArray,Updater,Double>> {
    protected static Logger log = LoggerFactory.getLogger(IterativeReduceFlatMap.class);

    protected String json;
    protected Broadcast<INDArray> params;
    protected Broadcast<Updater> updater;
    protected final Accumulator<Double> best_score_acc;

    /**
     * Pass in network configuration as json, broadcast parameters, broadcast updater and the bestScore to
     * fit mapped data set and update and return parameters, updater and best score.
     * @param json newtork string configuration
     * @param params broadcasted parameters to reload into network
     * @param updater broadcasted updaters to reload into network
     * @param bestScoreAcc accumulator which tracks best score seen
     */
    public IterativeReduceFlatMap(String json, Broadcast<INDArray> params, Broadcast<Updater> updater,
                                  Accumulator<Double> bestScoreAcc) {
        this.json = json;
        this.params = params;
        this.updater = updater;
        if(updater.getValue() == null)
            throw new IllegalArgumentException("Updater shouldn't be null");
        this.best_score_acc = bestScoreAcc;
    }

    @Override
    public Iterable<Tuple3<INDArray, Updater, Double>> call(Iterator<DataSet> dataSetIterator) throws Exception {
        if (!dataSetIterator.hasNext()) {
            return Collections.emptyList();
        }
        List<DataSet> collect = new ArrayList<>();
        while (dataSetIterator.hasNext()) {
            collect.add(dataSetIterator.next());
        }

        DataSet data = DataSet.merge(collect, false);
        if (log.isDebugEnabled()) {
            log.debug("Training on {} examples with data {}", data.numExamples(), data.labelCounts());
        }

        //Need to clone: parameters and updaters are mutable values -> .getValue() object will be shared by ALL executors on the same machine!
        INDArray val = params.getValue().dup();
        Updater upd = updater.getValue().clone();

        MultiLayerNetwork network = new MultiLayerNetwork(MultiLayerConfiguration.fromJson(json));
        network.init();
        network.setListeners(new ScoreIterationListener(1), new BestScoreIterationListener(best_score_acc));
        if (val.length() != network.numParams(false))
            throw new IllegalStateException("Network did not have same number of parameters as the broadcasted set parameters");
        network.setParameters(val);
        network.setUpdater(upd);
        network.fit(data);
        return Collections.singletonList(new Tuple3<>(network.params(false), network.getUpdater(), network.score()));
    }
}
