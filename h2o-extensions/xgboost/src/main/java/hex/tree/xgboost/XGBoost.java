package hex.tree.xgboost;

import hex.DataInfo;
import hex.ModelBuilder;
import hex.ModelCategory;
import hex.ScoreKeeper;
import hex.glm.GLMTask;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoostError;
import water.ExtensionManager;
import water.H2O;
import water.Job;
import water.Key;
import water.exceptions.H2OIllegalArgumentException;
import water.exceptions.H2OModelBuilderIllegalArgumentException;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.FileUtils;
import water.util.Log;
import water.util.Timer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static hex.tree.SharedTree.createModelSummaryTable;
import static hex.tree.SharedTree.createScoringHistoryTable;
import static water.H2O.technote;

/** Gradient Boosted Trees
 *
 *  Based on "Elements of Statistical Learning, Second Edition, page 387"
 */
public class XGBoost extends ModelBuilder<XGBoostModel,XGBoostModel.XGBoostParameters,XGBoostOutput> {
  @Override public boolean haveMojo() { return true; }

  @Override public BuilderVisibility builderVisibility() {
    if(ExtensionManager.getInstance().isCoreExtensionsEnabled(XGBoostExtension.NAME)){
      return BuilderVisibility.Stable;
    } else {
      return BuilderVisibility.Experimental;
    }
  }

  /**
   * convert an H2O Frame to a sparse DMatrix
   * @param f H2O Frame
   * @param response name of the response column
   * @param weight name of the weight column
   * @param fold name of the fold assignment column
   * @param featureMap featureMap[0] will be populated with the column names and types
   * @return DMatrix
   * @throws XGBoostError
   */
  public static DMatrix convertFrametoDMatrix(Key<DataInfo> dataInfoKey, Frame f, String response, String weight, String fold, String[] featureMap, boolean sparse) throws XGBoostError {

    DataInfo di = dataInfoKey.get();
    // set the names for the (expanded) columns
    if (featureMap!=null) {
      String[] coefnames = di.coefNames();
      StringBuilder sb = new StringBuilder();
      assert(coefnames.length == di.fullN());
      for (int i = 0; i < di.fullN(); ++i) {
        sb.append(i).append(" ").append(coefnames[i].replaceAll("\\s*","")).append(" ");
        int catCols = di._catOffsets[di._catOffsets.length-1];
        if (i < catCols || f.vec(i-catCols).isBinary())
          sb.append("i");
        else if (f.vec(i-catCols).isInt())
          sb.append("int");
        else
          sb.append("q");
        sb.append("\n");
      }
      featureMap[0] = sb.toString();
    }

    DMatrix trainMat;
    int nz = 0;
    int actualRows = 0;
    int nRows = (int) f.numRows();
    Vec.Reader w = weight == null ? null : f.vec(weight).new Reader();
    Vec.Reader[] vecs = new Vec.Reader[f.numCols()];
    for (int i = 0; i < vecs.length; ++i) {
      vecs[i] = f.vec(i).new Reader();
    }

    try {
      if (sparse) {
        Log.info("Treating matrix as sparse.");
        // 1 0 2 0
        // 4 0 0 3
        // 3 1 2 0
        boolean csc = false; //di._cats == 0;

        // truly sparse matrix - no categoricals
        // collect all nonzeros column by column (in parallel), then stitch together into final data structures
        if (csc) {

          // CSC:
//    long[] colHeaders = new long[] {0,        3,  4,     6,    7}; //offsets
//    float[] data = new float[]     {1f,4f,3f, 1f, 2f,2f, 3f};      //non-zeros down each column
//    int[] rowIndex = new int[]     {0,1,2,    2,  0, 2,  1};       //row index for each non-zero

          class SparseItem {
            int pos;
            double val;
          }
          int nCols = di._nums;

          List<SparseItem>[] col = new List[nCols]; //TODO: use more efficient storage (no GC)
          // allocate
          for (int i=0;i<nCols;++i) {
            col[i] = new ArrayList<>(Math.min(nRows, 10000));
          }

          // collect non-zeros
          int nzCount=0;
          for (int i=0;i<nCols;++i) { //TODO: parallelize over columns
            Vec v = f.vec(i);
            for (int c=0;c<v.nChunks();++c) {
              Chunk ck = v.chunkForChunkIdx(c);
              int[] nnz = new int[ck.sparseLenZero()];
              int nnzCount = ck.nonzeros(nnz);
              for (int k=0;k<nnzCount;++k) {
                SparseItem item = new SparseItem();
                int localIdx = nnz[k];
                item.pos = (int)ck.start() + localIdx;
                // both 0 and NA are omitted in the sparse DMatrix
                if (w != null && w.at(item.pos) == 0) continue;
                if (ck.isNA(localIdx)) continue;
                item.val = ck.atd(localIdx);
                col[i].add(item);
                nzCount++;
              }
            }
          }
          long[] colHeaders = new long[nCols + 1];
          float[] data = new float[nzCount];
          int[] rowIndex = new int[nzCount];
          // fill data for DMatrix
          for (int i=0;i<nCols;++i) { //TODO: parallelize over columns
            List sparseCol = col[i];
            colHeaders[i] = nz;
            for (int j=0;j<sparseCol.size();++j) {
              SparseItem si = (SparseItem)sparseCol.get(j);
              rowIndex[nz] = si.pos;
              data[nz] = (float)si.val;
              assert(si.val != 0);
              assert(!Double.isNaN(si.val));
              assert(w == null || w.at(si.pos) != 0);
              nz++;
            }
          }
          colHeaders[nCols] = nz;
          data = Arrays.copyOf(data, nz);
          rowIndex = Arrays.copyOf(rowIndex, nz);
          actualRows = countUnique(rowIndex);
          trainMat = new DMatrix(colHeaders, rowIndex, data, DMatrix.SparseType.CSC, actualRows);
          assert trainMat.rowNum() == actualRows;
        } else {

          // CSR:
//    long[] rowHeaders = new long[] {0,      2,      4,         7}; //offsets
//    float[] data = new float[]     {1f,2f,  4f,3f,  3f,1f,2f};     //non-zeros across each row
//    int[] colIndex = new int[]     {0, 2,   0, 3,   0, 1, 2};      //col index for each non-zero

          long[] rowHeaders = new long[nRows + 1];
          int initial_size = 1 << 20;
          float[] data = new float[initial_size];
          int[] colIndex = new int[initial_size];

          // extract predictors
          rowHeaders[0] = 0;
          for (int i = 0; i < nRows; ++i) {
            if (w != null && w.at(i) == 0) continue;
            int nzstart = nz;
            // enlarge final data arrays by 2x if needed
            while (data.length < nz + di._cats + di._nums) {
              int newLen = (int) Math.min((long) data.length << 1L, (long) (Integer.MAX_VALUE - 10));
              Log.info("Enlarging sparse data structure from " + data.length + " bytes to " + newLen + " bytes.");
              if (data.length == newLen) {
                throw new IllegalArgumentException(technote(11, "Data is too large to fit into the 32-bit Java float[] array that needs to be passed to the XGBoost C++ backend. Use H2O GBM instead."));
              }
              data = Arrays.copyOf(data, newLen);
              colIndex = Arrays.copyOf(colIndex, newLen);
            }
            for (int j = 0; j < di._cats; ++j) {
              if (!vecs[j].isNA(i)) {
                data[nz] = 1; //one-hot encoding
                colIndex[nz] = di.getCategoricalId(j, vecs[j].at8(i));
                nz++;
              } else {
                // NA == 0 for sparse -> no need to fill
//            data[nz] = 1; //one-hot encoding
//            colIndex[nz] = di.getCategoricalId(j, Double.NaN); //Fill NA bucket
//            nz++;
              }
            }
            for (int j = 0; j < di._nums; ++j) {
              float val = (float) vecs[di._cats + j].at(i);
              if (!Float.isNaN(val) && val != 0) {
                data[nz] = val;
                colIndex[nz] = di._catOffsets[di._catOffsets.length - 1] + j;
                nz++;
              }
            }
            if (nz == nzstart) {
              // for the corner case where there are no categorical values, and all numerical values are 0, we need to
              // assign a 0 value to any one column to have a consistent number of rows between the predictors and the special vecs (weight/response/etc.)
              data[nz] = 0;
              colIndex[nz] = 0;
              nz++;
            }
            rowHeaders[++actualRows] = nz;
          }
          data = Arrays.copyOf(data, nz);
          colIndex = Arrays.copyOf(colIndex, nz);
          rowHeaders = Arrays.copyOf(rowHeaders, actualRows + 1);
          trainMat = new DMatrix(rowHeaders, colIndex, data, DMatrix.SparseType.CSR, di.fullN());
          assert trainMat.rowNum() == actualRows;
        }
      } else {
        Log.info("Treating matrix as dense.");

        // extract predictors
        float[] data = new float[1 << 20];
        int cols = di.fullN();
        int pos = 0;
        for (int i = 0; i < nRows; ++i) {
          if (w != null && w.at(i) == 0) continue;
          // enlarge final data arrays by 2x if needed
          while (data.length < (actualRows + 1) * cols) {
            int newLen = (int) Math.min((long) data.length << 1L, (long) (Integer.MAX_VALUE - 10));
            Log.info("Enlarging dense data structure from " + data.length + " bytes to " + newLen + " bytes.");
            if (data.length == newLen) {
              throw new IllegalArgumentException(technote(11, "Data is too large to fit into the 32-bit Java float[] array that needs to be passed to the XGBoost C++ backend. Use H2O GBM instead."));
            }
            data = Arrays.copyOf(data, newLen);
          }
          for (int j = 0; j < di._cats; ++j) {
            if (vecs[j].isNA(i)) {
              data[pos + di.getCategoricalId(j, Double.NaN)] = 1; // fill NA bucket
            } else {
              data[pos + di.getCategoricalId(j, vecs[j].at8(i))] = 1;
            }
          }
          for (int j = 0; j < di._nums; ++j) {
            if (vecs[di._cats + j].isNA(i))
              data[pos + di._catOffsets[di._catOffsets.length - 1] + j] = Float.NaN;
            else
              data[pos + di._catOffsets[di._catOffsets.length - 1] + j] = (float) vecs[di._cats + j].at(i);
          }
          assert di._catOffsets[di._catOffsets.length - 1] + di._nums == cols;
          pos += cols;
          actualRows++;
        }
        data = Arrays.copyOf(data, actualRows * cols);
        trainMat = new DMatrix(data, actualRows, cols, Float.NaN);
        assert trainMat.rowNum() == actualRows;
      }
    } catch (NegativeArraySizeException e) {
      throw new IllegalArgumentException(technote(11, "Data is too large to fit into the 32-bit Java float[] array that needs to be passed to the XGBoost C++ backend. Use H2O GBM instead."));
    }

    // extract weight vector
    float[] weights = new float[actualRows];
    if (w != null) {
      int j = 0;
      for (int i = 0; i < nRows; ++i) {
        if (w.at(i) == 0) continue;
        weights[j++] = (float) w.at(i);
      }
      assert (j == actualRows);
    }

    // extract response vector
    Vec.Reader respVec = f.vec(response).new Reader();
    float[] resp = new float[actualRows];
    int j = 0;
    for (int i = 0; i < nRows; ++i) {
      if (w != null && w.at(i) == 0) continue;
      resp[j++] = (float) respVec.at(i);
    }
    assert (j == actualRows);
    resp = Arrays.copyOf(resp, actualRows);
    weights = Arrays.copyOf(weights, actualRows);

    trainMat.setLabel(resp);
    if (w!=null)
      trainMat.setWeight(weights);
//    trainMat.setGroup(null); //fold //FIXME - only needed if CV is internally done in XGBoost
    return trainMat;
  }

  @Override public ModelCategory[] can_build() {
    return new ModelCategory[]{
      ModelCategory.Regression,
      ModelCategory.Binomial,
      ModelCategory.Multinomial,
    };
  }

  // Called from an http request
  public XGBoost(XGBoostModel.XGBoostParameters parms                   ) { super(parms     ); init(false); }
  public XGBoost(XGBoostModel.XGBoostParameters parms, Key<XGBoostModel> key) { super(parms, key); init(false); }
  public XGBoost(boolean startup_once) { super(new XGBoostModel.XGBoostParameters(),startup_once); }
  public boolean isSupervised(){return true;}

  @Override protected int nModelsInParallel() {
    return 2;
  }

  /** Start the XGBoost training Job on an F/J thread. */
  @Override protected XGBoostDriver trainModelImpl() {
    return new XGBoostDriver();
  }

  /** Initialize the ModelBuilder, validating all arguments and preparing the
   *  training frame.  This call is expected to be overridden in the subclasses
   *  and each subclass will start with "super.init();".  This call is made
   *  by the front-end whenever the GUI is clicked, and needs to be fast;
   *  heavy-weight prep needs to wait for the trainModel() call.
   *
   *  Validate the learning rate and distribution family. */
  @Override public void init(boolean expensive) {
    super.init(expensive);
    if (expensive) {
      if (_response.naCnt() > 0)
        error("_response_column", "Response contains missing values (NAs) - not supported by XGBoost.");
    }

    // Initialize response based on given distribution family.
    // Regression: initially predict the response mean
    // Binomial: just class 0 (class 1 in the exact inverse prediction)
    // Multinomial: Class distribution which is not a single value.

    // However there is this weird tension on the initial value for
    // classification: If you guess 0's (no class is favored over another),
    // then with your first GBM tree you'll typically move towards the correct
    // answer a little bit (assuming you have decent predictors) - and
    // immediately the Confusion Matrix shows good results which gradually
    // improve... BUT the Means Squared Error will suck for unbalanced sets,
    // even as the CM is good.  That's because we want the predictions for the
    // common class to be large and positive, and the rare class to be negative
    // and instead they start around 0.  Guessing initial zero's means the MSE
    // is so bad, that the R^2 metric is typically negative (usually it's
    // between 0 and 1).

    // If instead you guess the mean (reversed through the loss function), then
    // the zero-tree XGBoost model reports an MSE equal to the response variance -
    // and an initial R^2 of zero.  More trees gradually improves the R^2 as
    // expected.  However, all the minority classes have large guesses in the
    // wrong direction, and it takes a long time (lotsa trees) to correct that
    // - so your CM sucks for a long time.
    if (expensive) {
      if (error_count() > 0)
        throw H2OModelBuilderIllegalArgumentException.makeFromBuilder(XGBoost.this);
      if (hasOffsetCol()) {
        error("_offset_column", "Offset is not supported for XGBoost.");
      }
    }

    if (H2O.CLOUD.size()>1) {
      throw new IllegalArgumentException("XGBoost is currently only supported in single-node mode.");
    }
    if ( _parms._backend == XGBoostModel.XGBoostParameters.Backend.gpu && !hasGPU(_parms._gpu_id)) {
      error("_backend", "GPU backend (gpu_id: " + _parms._gpu_id + ") is not functional. Check CUDA_PATH and/or GPU installation.");
    }

    switch( _parms._distribution) {
    case bernoulli:
      if( _nclass != 2 /*&& !couldBeBool(_response)*/)
        error("_distribution", technote(2, "Binomial requires the response to be a 2-class categorical"));
      break;
    case modified_huber:
      if( _nclass != 2 /*&& !couldBeBool(_response)*/)
        error("_distribution", technote(2, "Modified Huber requires the response to be a 2-class categorical."));
      break;
    case multinomial:
      if (!isClassifier()) error("_distribution", technote(2, "Multinomial requires an categorical response."));
      break;
    case huber:
      if (isClassifier()) error("_distribution", technote(2, "Huber requires the response to be numeric."));
      break;
    case poisson:
      if (isClassifier()) error("_distribution", technote(2, "Poisson requires the response to be numeric."));
      break;
    case gamma:
      if (isClassifier()) error("_distribution", technote(2, "Gamma requires the response to be numeric."));
      break;
    case tweedie:
      if (isClassifier()) error("_distribution", technote(2, "Tweedie requires the response to be numeric."));
      break;
    case gaussian:
      if (isClassifier()) error("_distribution", technote(2, "Gaussian requires the response to be numeric."));
      break;
    case laplace:
      if (isClassifier()) error("_distribution", technote(2, "Laplace requires the response to be numeric."));
      break;
    case quantile:
      if (isClassifier()) error("_distribution", technote(2, "Quantile requires the response to be numeric."));
      break;
    case AUTO:
      break;
    default:
      error("_distribution","Invalid distribution: " + _parms._distribution);
    }

    if( !(0. < _parms._learn_rate && _parms._learn_rate <= 1.0) )
      error("_learn_rate", "learn_rate must be between 0 and 1");
    if( !(0. < _parms._col_sample_rate && _parms._col_sample_rate <= 1.0) )
      error("_col_sample_rate", "col_sample_rate must be between 0 and 1");
    if (_parms._grow_policy== XGBoostModel.XGBoostParameters.GrowPolicy.lossguide && _parms._tree_method!= XGBoostModel.XGBoostParameters.TreeMethod.hist)
      error("_grow_policy", "must use tree_method=hist for grow_policy=lossguide");
  }

  static DataInfo makeDataInfo(Frame train, Frame valid, XGBoostModel.XGBoostParameters parms, int nClasses) {
    DataInfo dinfo = new DataInfo(
            train,
            valid,
            1, //nResponses
            true, //all factor levels
            DataInfo.TransformType.NONE, //do not standardize
            DataInfo.TransformType.NONE, //do not standardize response
            parms._missing_values_handling == XGBoostModel.XGBoostParameters.MissingValuesHandling.Skip, //whether to skip missing
            false, // do not replace NAs in numeric cols with mean
            true,  // always add a bucket for missing values
            parms._weights_column != null, // observation weights
            parms._offset_column != null,
            parms._fold_column != null
    );
    // Checks and adjustments:
    // 1) observation weights (adjust mean/sigmas for predictors and response)
    // 2) NAs (check that there's enough rows left)
    GLMTask.YMUTask ymt = new GLMTask.YMUTask(dinfo, nClasses,nClasses == 1, parms._missing_values_handling == XGBoostModel.XGBoostParameters.MissingValuesHandling.Skip, true, true).doAll(dinfo._adaptedFrame);
    if (ymt.wsum() == 0 && parms._missing_values_handling == XGBoostModel.XGBoostParameters.MissingValuesHandling.Skip)
      throw new H2OIllegalArgumentException("No rows left in the dataset after filtering out rows with missing values. Ignore columns with many NAs or set missing_values_handling to 'MeanImputation'.");
    if (parms._weights_column != null && parms._offset_column != null) {
      Log.warn("Combination of offset and weights can lead to slight differences because Rollupstats aren't weighted - need to re-calculate weighted mean/sigma of the response including offset terms.");
    }
    if (parms._weights_column != null && parms._offset_column == null /*FIXME: offset not yet implemented*/) {
      dinfo.updateWeightedSigmaAndMean(ymt.predictorSDs(), ymt.predictorMeans());
      if (nClasses == 1)
        dinfo.updateWeightedSigmaAndMeanForResponse(ymt.responseSDs(), ymt.responseMeans());
    }
    return dinfo;
  }

  // ----------------------
  private class XGBoostDriver extends Driver {

    private static final String FEATURE_MAP_FILENAME = "featureMap.txt";

    @Override
    public void computeImpl() {
      init(true); //this can change the seed if it was set to -1
      long cs = _parms.checksum();
      // Something goes wrong
      if (error_count() > 0)
        throw H2OModelBuilderIllegalArgumentException.makeFromBuilder(XGBoost.this);
      buildModel();
      //check that _parms isn't changed during DL model training
//      long cs2 = _parms.checksum(); //they can change now (when the user specifies a parameter in XGBoost convention) - otherwise, need to check two different parameters everywhere...
//      assert(cs == cs2);
    }

    final void buildModel() {
      XGBoostModel model = new XGBoostModel(_result,_parms,new XGBoostOutput(XGBoost.this),_train,_valid);
      model.write_lock(_job);
      String[] featureMap = new String[]{""};

      if (_parms._dmatrix_type == XGBoostModel.XGBoostParameters.DMatrixType.sparse) {
        model._output._sparse = true;
      } else if (_parms._dmatrix_type == XGBoostModel.XGBoostParameters.DMatrixType.dense) {
        model._output._sparse = false;
      } else {
        float fillRatio = 0;
        int col = 0;
        for (int i = 0; i < _train.numCols(); ++i) {
          if (_train.name(i).equals(_parms._response_column)) continue;
          if (_train.name(i).equals(_parms._weights_column)) continue;
          if (_train.name(i).equals(_parms._fold_column)) continue;
          if (_train.name(i).equals(_parms._offset_column)) continue;
          fillRatio += _train.vec(i).nzCnt() / _train.numRows();
          col++;
        }
        fillRatio /= col;
        Log.info("fill ratio: " + fillRatio);
        model._output._sparse = fillRatio < 0.5 || ((_train.numRows() * (long) _train.numCols()) > Integer.MAX_VALUE);
      }

      try {
        DMatrix trainMat = convertFrametoDMatrix( model.model_info()._dataInfoKey, _train,
            _parms._response_column, _parms._weights_column, _parms._fold_column, featureMap, model._output._sparse);

        DMatrix validMat = _valid != null ? convertFrametoDMatrix(model.model_info()._dataInfoKey, _valid,
            _parms._response_column, _parms._weights_column, _parms._fold_column, featureMap, model._output._sparse) : null;

        // For feature importances - write out column info
        OutputStream os = null;
        File tmpModelDir = null;
        try {
          tmpModelDir = java.nio.file.Files.createTempDirectory("xgboost-model-" + _result.toString()).toFile();
          os = new FileOutputStream(new File(tmpModelDir, FEATURE_MAP_FILENAME));
          os.write(featureMap[0].getBytes());
        } catch (IOException e) {
          H2O.fail("Cannot generate " + FEATURE_MAP_FILENAME, e);
        } finally {
          FileUtils.close(os);
        }

        // create the backend
        HashMap<String, DMatrix> watches = new HashMap<>();
        model.model_info()._booster = ml.dmlc.xgboost4j.java.XGBoost.train(trainMat, model.createParams(), 0, watches, null, null);

        // train the model
        scoreAndBuildTrees(model, trainMat, validMat, tmpModelDir);

        // final scoring
        doScoring(model, model.model_info()._booster, trainMat, validMat, true, tmpModelDir);

        // save the model to DKV
        model.model_info().nativeToJava();
      } catch (XGBoostError xgBoostError) {
        xgBoostError.printStackTrace();
        H2O.fail("XGBoost failure", xgBoostError);
      }
      model._output._boosterBytes = model.model_info()._boosterBytes;
      model.unlock(_job);
    }

    protected final void scoreAndBuildTrees(XGBoostModel model, DMatrix trainMat, DMatrix validMat, final File tmpModelDir) throws XGBoostError {
      for( int tid=0; tid< _parms._ntrees; tid++) {
        // During first iteration model contains 0 trees, then 1-tree, ...
        boolean scored = doScoring(model, model.model_info()._booster, trainMat, validMat, false, tmpModelDir);
        if (scored && ScoreKeeper.stopEarly(model._output.scoreKeepers(), _parms._stopping_rounds, _nclass > 1, _parms._stopping_metric, _parms._stopping_tolerance, "model's last", true)) {
          doScoring(model, model.model_info()._booster, trainMat, validMat, true, tmpModelDir);
          _job.update(_parms._ntrees-model._output._ntrees); //finish
          return;
        }

        Timer kb_timer = new Timer();
        try {
//          model.model_info()._booster.setParam("eta", effective_learning_rate(model));
          model.model_info()._booster.update(trainMat, tid);
        } catch (XGBoostError xgBoostError) {
          xgBoostError.printStackTrace();
        }
        Log.info((tid + 1) + ". tree was built in " + kb_timer.toString());
        _job.update(1);
        // Optional: for convenience
//          model.update(_job);
//          model.model_info().nativeToJava();
        model._output._ntrees++;
        model._output._scored_train = ArrayUtils.copyAndFillOf(model._output._scored_train, model._output._ntrees+1, new ScoreKeeper());
        model._output._scored_valid = model._output._scored_valid != null ? ArrayUtils.copyAndFillOf(model._output._scored_valid, model._output._ntrees+1, new ScoreKeeper()) : null;
        model._output._training_time_ms = ArrayUtils.copyAndFillOf(model._output._training_time_ms, model._output._ntrees+1, System.currentTimeMillis());
        if (stop_requested() && !timeout()) throw new Job.JobCancelledException();
        if (timeout()) { //stop after scoring
          if (!scored) doScoring(model, model.model_info()._booster, trainMat, validMat, true, tmpModelDir);
          _job.update(_parms._ntrees-model._output._ntrees); //finish
          break;
        }
      }
      doScoring(model, model.model_info()._booster, trainMat, validMat, true, tmpModelDir);
    }

    long _firstScore = 0;
    long _timeLastScoreStart = 0;
    long _timeLastScoreEnd = 0;
    
    private boolean doScoring(XGBoostModel model, Booster booster, DMatrix trainMat, DMatrix validMat, boolean finalScoring, File tmpModelDir) throws XGBoostError {
      boolean scored = false;
      long now = System.currentTimeMillis();
      if (_firstScore == 0) _firstScore = now;
      long sinceLastScore = now - _timeLastScoreStart;
      _job.update(0, "Built " + model._output._ntrees + " trees so far (out of " + _parms._ntrees + ").");

      boolean timeToScore = (now - _firstScore < _parms._initial_score_interval) || // Score every time for 4 secs
          // Throttle scoring to keep the cost sane; limit to a 10% duty cycle & every 4 secs
          (sinceLastScore > _parms._score_interval && // Limit scoring updates to every 4sec
              (double) (_timeLastScoreEnd - _timeLastScoreStart) / sinceLastScore < 0.1); //10% duty cycle

      boolean manualInterval = _parms._score_tree_interval > 0 && model._output._ntrees % _parms._score_tree_interval == 0;

      // Now model already contains tid-trees in serialized form
      if (_parms._score_each_iteration || finalScoring || // always score under these circumstances
          (timeToScore && _parms._score_tree_interval == 0) || // use time-based duty-cycle heuristic only if the user didn't specify _score_tree_interval
          manualInterval) {
        _timeLastScoreStart = now;
        model.doScoring(booster, trainMat, _parms.train(), validMat, _parms.valid());
        _timeLastScoreEnd = System.currentTimeMillis();
        model.computeVarImp(booster.getFeatureScore(new File(tmpModelDir, FEATURE_MAP_FILENAME).getAbsolutePath()));
        XGBoostOutput out = model._output;
        out._model_summary = createModelSummaryTable(out._ntrees, null);
        out._scoring_history = createScoringHistoryTable(out, model._output._scored_train, out._scored_valid, _job, out._training_time_ms);
        out._variable_importances = hex.ModelMetrics.calcVarImp(out._varimp);
        model.update(_job);
        Log.info(model);
        scored = true;
      }
      return scored;
    }
  }

  private double effective_learning_rate(XGBoostModel model) {
    return _parms._learn_rate * Math.pow(_parms._learn_rate_annealing, (model._output._ntrees-1));
  }

  // helper
  static boolean hasGPU(int gpu_id) {
    DMatrix trainMat = null;
    try {
      trainMat = new DMatrix(new float[]{1,2,1,2},2,2);
      trainMat.setLabel(new float[]{1,0});
    } catch (XGBoostError xgBoostError) {
      xgBoostError.printStackTrace();
    }

    HashMap<String, Object> params = new HashMap<>();
    params.put("updater", "grow_gpu_hist");
    params.put("silent", 1);
    params.put("gpu_id", gpu_id);
    HashMap<String, DMatrix> watches = new HashMap<>();
    watches.put("train", trainMat);
    try {
      ml.dmlc.xgboost4j.java.XGBoost.train(trainMat, params, 1, watches, null, null);
      return true;
    } catch (XGBoostError xgBoostError) {
      return false;
    }
  }

  public static int countUnique(int[] unsortedArray) {
    if (unsortedArray.length == 0) {
      return 0;
    }
    int[] array = Arrays.copyOf(unsortedArray, unsortedArray.length);
    Arrays.sort(array);
    int count = 1;
    for (int i = 0; i < array.length - 1; i++) {
      if (array[i] != array[i + 1]) {
        count++;
      }
    }
    return count;
  }
}