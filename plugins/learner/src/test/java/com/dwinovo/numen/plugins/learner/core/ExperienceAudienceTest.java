package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A6（98 号）：experience 的「给谁用」是可选键；缺失 = 空 = legacy。 */
class ExperienceAudienceTest {

    private static Experience parse(String json) {
        return Experience.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesAndNormalizesAudience() {
        Experience x = parse("{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\","
                + "\"failureConditions\":\"f\",\"observableSignal\":\"s\",\"derivation\":\"d\","
                + "\"efficiency\":\"e\",\"evidence\":\"v\",\"experienceType\":\"EXECUTION\","
                + "\"audience\":[\"planner\",\"EXECUTOR\",\"boss\"]}}");
        assertEquals(List.of("PLANNER", "EXECUTOR"), x.audience(), "只收三种合法角色，去重并大写");
        assertTrue(x.acceptable(), "多了可选 audience 不应让条目不合格");
    }

    @Test
    void missingAudienceIsEmptyNotGuessed() {
        Experience x = parse("{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\","
                + "\"failureConditions\":\"f\",\"observableSignal\":\"s\",\"derivation\":\"d\","
                + "\"efficiency\":\"e\",\"evidence\":\"v\",\"experienceType\":\"EXECUTION\"}}");
        assertTrue(x.audience().isEmpty(), "没写就不标注，不许默认全给");
    }

    @Test
    void singleStringAudienceTolerated() {
        Experience x = parse("{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\","
                + "\"failureConditions\":\"f\",\"observableSignal\":\"s\",\"derivation\":\"d\","
                + "\"efficiency\":\"e\",\"evidence\":\"v\",\"experienceType\":\"POLICY\","
                + "\"audience\":\"learner\"}}");
        assertEquals(List.of("LEARNER"), x.audience());
    }
}
