package org.msk.zigbee.mapper;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemplatingService {

    static {
        groovyScript = new GroovyShell().parse("""
                def translate(mappingFormula, payloadString, attributeName) {
                    def engine = new groovy.text.GStringTemplateEngine()
                    def payload =  new groovy.json.JsonSlurper().parse(payloadString)
                    def value = payload[attributeName]
                    def template = engine.createTemplate(mappingFormula).make([value: value])
                    return template.toString()
                }
                """);
    }

    private static final Script groovyScript;

    public String processTemplate(String template, String payload, String attributeName) {
        return groovyScript.invokeMethod("translate", new Object[] {template, payload.getBytes(StandardCharsets.UTF_8), attributeName}).toString();
    }
}
