/*
 * Copyright 2018 Liu Bo
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.abc.common.springmvc;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abc.common.AbstractController;
import com.abc.common.servlet.NodeDeploymentDescriptor;
import com.abc.common.util.LogWriter;

/**
 * Title: HelloController
 * @Description: HelloController
 * @author Bo Liu
 * @date 2018-09-20
 */
@RestController
@RequestMapping
public class HelloController extends AbstractController {

	/** 
	 * @Description: hello
	 * @return  String
	 * @throws 
	 */ 
	@RequestMapping(value = "/hello", produces = MediaType.TEXT_PLAIN_VALUE)
	public String hello() {

		String str = NodeDeploymentDescriptor.getInstance().toString();
		return str;

	}

	/** 
	 * @Description: uan 
	 * @throws 
	 */ 
	@RequestMapping(value = "/uan/*")
	public void uan() {
		LogWriter.info(HelloController.class, "一个uan的请求");
	}

}
