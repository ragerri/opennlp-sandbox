/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.opennlp.caseditor.namefinder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameFinderSequenceValidator;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.Span;

import org.apache.opennlp.caseditor.OpenNLPPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

// Add error handling, if something goes wrong, an error should be reported!
// Need a rule, only one name finder job at a time ...
// don't change setting, while job is running!
public class NameFinderJob extends Job {
  
  // TODO: It should be changed in a way that detected annotations are always
  // a perfect match with existing annotations
  static class RestrictedSequencesValidator extends NameFinderSequenceValidator {
    
    private Map<Integer, String> nameIndex = new HashMap<Integer, String>();
    
    // also give it a no-name index
    void setRestriction(Map<Integer, String> nameIndex) {
      this.nameIndex = nameIndex;
    }
    
    @Override
    public boolean validSequence(int i, String[] inputSequence,
        String[] outcomesSequence, String outcome) {
      boolean valid = super.validSequence(i, inputSequence, outcomesSequence, outcome);
      
      if (valid && nameIndex.get(i) != null) {
        String desiredOutcome = nameIndex.get(i);
        return outcome.endsWith(desiredOutcome);
      }
      
      return valid;
    }
  }
  
  private NameFinderME nameFinder;
  private RestrictedSequencesValidator sequenceValidator;
  
  private String modelPath;
  private String text;
  private Span sentences[];
  private Span tokens[];
  private Span verifiedNames[] = new Span[0];
  
  private List<Entity> nameList;
  
  NameFinderJob() {
    super("Name Finder Job");
  }
  
  synchronized void setModelPath(String modelPath) {
    this.modelPath = modelPath;
  }
  
  synchronized void setText(String text) {
    this.text = text;
  }
  
  synchronized void setSentences(Span sentences[]) {
    this.sentences = sentences;
  }
  
  synchronized void setTokens(Span tokens[]) {
    this.tokens = tokens;
  }
  
  synchronized void setVerifiedNames(Span verifiedNames[]) {
    this.verifiedNames = verifiedNames;
  }

  // maybe report result, through an interface?!
  @Override
  protected IStatus run(IProgressMonitor monitor) {

    // lazy load model on first run ...
    if (nameFinder == null) {
      InputStream modelIn = null;
      try {
        modelIn = NameFinderViewPage.class
            .getResourceAsStream("/en-ner-per.bin");
        TokenNameFinderModel model = new TokenNameFinderModel(modelIn);
        sequenceValidator = new RestrictedSequencesValidator();
        nameFinder = new NameFinderME(model, null, 5, sequenceValidator);
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        if (modelIn != null) {
          try {
            modelIn.close();
          } catch (IOException e) {
          }
        }
      }
    }

    if (nameFinder != null) {
      nameFinder.clearAdaptiveData();
    
      nameList = new ArrayList<Entity>();
      
      for (Span sentence : sentences) {
        
        // Create token list for sentence
        List<Span> sentenceTokens = new ArrayList<Span>();
        
        for (Span token : tokens) {
          if (sentence.contains(token)) {
            sentenceTokens.add(token);
          }
        }
        
        String tokenStrings[] = new String[sentenceTokens.size()];
        
        for (int i = 0; i < sentenceTokens.size(); i++) {
          Span token = sentenceTokens.get(i);
          tokenStrings[i] = token.getCoveredText(text).toString();
        }
        
        Map<Integer, String> verifiedNameTokens = new HashMap<Integer, String>();
        
        // Note: This is slow!
        // iterate over names, to find token indexes
        for (Span verifiedName : verifiedNames) {
          for (int i = 0; i < sentenceTokens.size(); i++) {
            boolean isStart = true;
            if (verifiedName.contains(sentenceTokens.get(i))) {
              
              String outcome;
              
              // Need better mechanism here, first token in entity should be start!
              if (isStart) {
                outcome = NameFinderME.START;
                isStart = false;
              }
              else 
                outcome = NameFinderME.CONTINUE;
              
              verifiedNameTokens.put(i, outcome);
            }
          }
        }
        
        sequenceValidator.setRestriction(verifiedNameTokens);
        
        Span names[] = nameFinder.find(tokenStrings);
        double nameProbs[] = nameFinder.probs(names);
        
        for (int i = 0; i < names.length; i++) {
          
          // add sentence offset here ...
          
          int beginIndex = sentenceTokens.get(names[i].getStart()).getStart();
          int endIndex = sentenceTokens.get(names[i].getEnd() - 1).getEnd();
          
          String coveredText = text.substring(beginIndex, endIndex);
          
          
          nameList.add(new Entity(beginIndex, endIndex, coveredText, nameProbs[i], false));
        }
      }
    }
    
    // TODO: If there is a problem return an error status,
    // and calling client can fetch error message via method call
    // Use OpenNLPPlugin to log errors ...
    return new Status(IStatus.OK, OpenNLPPlugin.ID, "OK");
  }

  public List<Entity> getNames() {
    List<Entity> names = new ArrayList<Entity>();
    names.addAll(nameList);
    return names;
  }
}
