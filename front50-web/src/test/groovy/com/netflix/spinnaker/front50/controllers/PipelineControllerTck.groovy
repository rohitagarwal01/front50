/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.front50.controllers

import com.netflix.spinnaker.front50.model.S3StorageService
import com.netflix.spinnaker.front50.model.pipeline.DefaultPipelineDAO

import java.util.concurrent.Executors
import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.AmazonS3Client
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.front50.model.pipeline.Pipeline
import com.netflix.spinnaker.front50.model.pipeline.PipelineDAO
import com.netflix.spinnaker.front50.pipeline.PipelineRepository
import com.netflix.spinnaker.front50.utils.CassandraTestHelper
import com.netflix.spinnaker.front50.utils.S3TestHelper
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import rx.schedulers.Schedulers
import spock.lang.*
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

abstract class PipelineControllerTck extends Specification {

  static final int OK = 200
  static final int BAD_REQUEST = 400
  static final int UNPROCESSABLE_ENTITY = 422

  MockMvc mockMvc

  @Subject PipelineDAO pipelineDAO

  void setup() {
    this.pipelineDAO = createPipelineDAO()

    mockMvc = MockMvcBuilders.standaloneSetup(
        new PipelineController(pipelineDAO: pipelineDAO)).build()
  }

  abstract PipelineDAO createPipelineDAO()

  def "should fail to save if application is missing"() {
    given:
    def command = [
      name: "some pipeline with no application"
    ]

    when:
    def response = mockMvc
      .perform(
      post("/pipelines")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(command))
    )
      .andReturn()
      .response

    then:
    response.status == UNPROCESSABLE_ENTITY
  }

  void 'return 200 for successful rename'() {
    given:
    def pipeline = pipelineDAO.create(null, new Pipeline([name: "old-pipeline-name", application: "test"]))
    def command = [
        application: 'test',
        from       : 'old-pipeline-name',
        to         : 'new-pipeline-name'
    ]

    when:
    def response = mockMvc.perform(post('/pipelines/move').
        contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(command)))
        .andReturn().response

    then:
    response.status == OK
    pipelineDAO.findById(pipeline.getId()).getName() == "new-pipeline-name"
  }

  @Unroll
  void 'should only (re)generate cron trigger ids for new pipelines'() {
    given:
    def pipeline = [
        name       : "My Pipeline",
        application: "test",
        triggers   : [
            [type: "cron", id: "original-id"]
        ]
    ]
    if (lookupPipelineId) {
      pipelineDAO.create(null, pipeline as Pipeline)
      pipeline.id = pipelineDAO.findById(
          pipelineDAO.getPipelineId("test", "My Pipeline")
      ).getId()
    }

    when:
    def response = mockMvc.perform(post('/pipelines').
        contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(pipeline)))
        .andReturn().response

    def updatedPipeline = pipelineDAO.findById(
        pipelineDAO.getPipelineId("test", "My Pipeline")
    )

    then:
    response.status == OK
    expectedTriggerCheck.call(updatedPipeline)

    where:
    lookupPipelineId || expectedTriggerCheck
    false            || { Map p -> p.triggers*.id != ["original-id"] }
    true             || { Map p -> p.triggers*.id == ["original-id"] }
  }

  void 'should delete an existing pipeline by name or id'() {
    given:
    pipelineDAO.create(null, new Pipeline([
        name: "pipeline1", application: "test"
    ]))
    pipelineDAO.create(null, new Pipeline([
        name: "pipeline2", application: "test"
    ]))

    when:
    def allPipelines = pipelineDAO.all()
    def allPipelinesForApplication = pipelineDAO.getPipelinesByApplication("test")

    then:
    allPipelines*.id.sort() == allPipelinesForApplication*.id.sort()
    allPipelines.size() == 2

    when:
    def response = mockMvc.perform(delete('/pipelines/test/pipeline1')).andReturn().response

    then:
    response.status == OK
    pipelineDAO.all()*.name == ["pipeline2"]
  }

  void 'should enforce unique names on save operations'() {
    given:
    pipelineDAO.create(null, new Pipeline([
            name: "pipeline1", application: "test"
    ]))
    pipelineDAO.create(null, new Pipeline([
            name: "pipeline2", application: "test"
    ]))

    when:
    def allPipelines = pipelineDAO.all()
    def allPipelinesForApplication = pipelineDAO.getPipelinesByApplication("test")

    then:
    allPipelines*.id.sort() == allPipelinesForApplication*.id.sort()
    allPipelines.size() == 2

    when:
    def response = mockMvc.perform(post('/pipelines')
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString([name: "pipeline1", application: "test"])))
                  .andReturn().response

    then:
    response.status == BAD_REQUEST
    response.contentAsString == '{"error":"A pipeline with that name already exists in that application","status":"BAD_REQUEST"}'
  }

  void 'should enforce unique names on rename operations'() {
    given:
    pipelineDAO.create(null, new Pipeline([
            name: "pipeline1", application: "test"
    ]))
    pipelineDAO.create(null, new Pipeline([
            name: "pipeline2", application: "test"
    ]))

    when:
    def response = mockMvc.perform(post('/pipelines/move')
            .contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString([from: "pipeline2", to: "pipeline1", application: "test"])))
            .andReturn().response

    then:
    response.status == BAD_REQUEST
    response.contentAsString == '{"error":"A pipeline with that name already exists in that application","status":"BAD_REQUEST"}'
  }
}

class CassandraPipelineControllerTck extends PipelineControllerTck {
  @Shared
  CassandraTestHelper cassandraHelper = new CassandraTestHelper()

  @Shared
  PipelineRepository pipelineRepository

  @Override
  PipelineDAO createPipelineDAO() {
    pipelineRepository = new PipelineRepository(keyspace: cassandraHelper.keyspace)
    pipelineRepository.init()

    pipelineRepository.runQuery('''TRUNCATE pipeline''')

    return pipelineRepository
  }
}

@IgnoreIf({ S3TestHelper.s3ProxyUnavailable() })
class S3PipelineControllerTck extends PipelineControllerTck {
  @Shared
  def scheduler = Schedulers.from(Executors.newFixedThreadPool(1))

  @Shared
  PipelineDAO pipelineDAO

  @Override
  PipelineDAO createPipelineDAO() {
    def amazonS3 = new AmazonS3Client(new ClientConfiguration())
    amazonS3.setEndpoint("http://127.0.0.1:9999")
    S3TestHelper.setupBucket(amazonS3, "front50")

    def storageService = new S3StorageService(new ObjectMapper(), amazonS3, "front50", "test")
    pipelineDAO = new DefaultPipelineDAO(storageService, scheduler, 0)

    return pipelineDAO
  }
}
