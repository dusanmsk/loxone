package org.msk.zigbee.mapper.ui;

import com.google.common.collect.Sets;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Route
@UIScope
//@Push
//@PreserveOnRefresh
@SpringComponent
//@RequiredArgsConstructor
@Slf4j
public class DevelView extends VerticalLayout {

    public DevelView() {
        Set<String> knownItems = Sets.newHashSet("a", "b", "c");
        ComboBox<String> comboBox = new ComboBox<>("Test");
        comboBox.setItems(knownItems);
        comboBox.setAllowCustomValue(true);
        comboBox.addCustomValueSetListener(i -> {
            knownItems.add(i.getDetail());
            comboBox.setItems(knownItems);
            comboBox.setValue(i.getDetail());
        });

        Button button = new Button("Save");
        button.addClickListener(event -> {
            System.out.println("Save " + comboBox.getValue());
        });

        add(comboBox);
        add(button);
    }
}
