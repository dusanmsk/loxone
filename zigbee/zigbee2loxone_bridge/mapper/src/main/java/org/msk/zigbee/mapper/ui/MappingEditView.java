package org.msk.zigbee.mapper.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import java.util.ArrayList;
import java.util.Collection;
import org.msk.zigbee.TemplatingService;
import org.msk.zigbee.mapper.LoxoneService;
import org.msk.zigbee.mapper.configs.Configuration;
import org.springframework.beans.factory.annotation.Autowired;

@SpringComponent
@Route
@UIScope
public class MappingEditView extends VerticalLayout {

    private final LoxoneService loxoneService;
    private final TemplatingService templatingService;

    private TextField deviceNameTextField;
    private Grid<Configuration.Mapping.PayloadMapping> loxoneToZigbeeMappingGrid;
    private Grid<Configuration.Mapping.PayloadMapping> zigbeeToLoxoneMappingGrid;

    private Configuration.Mapping mapping;

    public MappingEditView(@Autowired LoxoneService loxoneService, @Autowired TemplatingService templatingService) {
        this.loxoneService = loxoneService;
        this.templatingService = templatingService;
        deviceNameTextField = new TextField("Zigbee device name:");
        deviceNameTextField.setReadOnly(true);

        loxoneToZigbeeMappingGrid = createPayloadMappingGrid();
        zigbeeToLoxoneMappingGrid = createPayloadMappingGrid();

        add(deviceNameTextField);
        add(new Label("Loxone to zigbee mappings:"));
        add(loxoneToZigbeeMappingGrid);

        add(new Label("Zigbee to loxone mappings:"));
        add(zigbeeToLoxoneMappingGrid);

    }

    public void editMapping(Configuration.Mapping mapping) {
        this.mapping = mapping;
        refreshData();
    }

    private void refreshData() {
        deviceNameTextField.setValue(mapping.getZigbeeDeviceName());
        loxoneToZigbeeMappingGrid.setItems(mapping.getL2zPayloadMappings());
        zigbeeToLoxoneMappingGrid.setItems(mapping.getZ2lPayloadMappings());
    }

    private Grid<Configuration.Mapping.PayloadMapping> createPayloadMappingGrid() {
        Grid<Configuration.Mapping.PayloadMapping> grid = new Grid<>();
        grid.addColumn(Configuration.Mapping.PayloadMapping::getLoxoneComponentName).setHeader("Loxone component name");
        grid.addColumn(Configuration.Mapping.PayloadMapping::getMappingFormula).setHeader("Mapping formula");
        grid.addItemDoubleClickListener(e -> editPayloadMapping(e.getItem()));
        return grid;
    }

    private void editPayloadMapping(Configuration.Mapping.PayloadMapping payload) {
        Dialog dialog = createPayloadMappingDialog(payload);
        dialog.open();

        add(dialog);
    }

    // todo dusan.zatkovsky live transformation testing
    private Dialog createPayloadMappingDialog(Configuration.Mapping.PayloadMapping payload) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Edit mapping");
        VerticalLayout dialogLayout = new VerticalLayout();
        ComboBox<String> loxoneComponentNameCombobox = createLoxoneComponentNameComboBox();
        TextField mappingFormulaEditor = createMappingFormulaEditor();
        loxoneComponentNameCombobox.setValue(payload.getLoxoneComponentName());
        mappingFormulaEditor.setValue(payload.getMappingFormula());
        SamplePayloadSelector samplePayloadSelector = new SamplePayloadSelector(loxoneService.getPayloadSamples(payload.getLoxoneComponentName())) {

            @Override
            public String translateValue(String payload) {
                return templatingService.processTemplate(mappingFormulaEditor.getValue(), payload);

            }
        };
        dialogLayout.add(loxoneComponentNameCombobox, mappingFormulaEditor, samplePayloadSelector);
        dialog.setWidth("40%");
        dialog.setResizable(true);
        dialog.add(dialogLayout);

        Button saveButton = new Button("Save", e -> {
            payload.setLoxoneComponentName(loxoneComponentNameCombobox.getValue());
            payload.setMappingFormula(mappingFormulaEditor.getValue());
            refreshData();
            dialog.close();
        });
        Button cancelButton = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelButton);
        dialog.getFooter().add(saveButton);

        //dialog.setModal(true);
        return dialog;

    }

    private TextField createMappingFormulaEditor() {
        TextField editor = new TextField("Mapping formula:");
        editor.setSizeFull();
        return editor;
    }

    private ComboBox<String> createLoxoneComponentNameComboBox() {
        ComboBox.ItemFilter<String> filter = (i, filterString) -> i.toLowerCase().contains(filterString.toLowerCase());
        ComboBox<String> comboBox = new ComboBox<>("Zigbee component name:");
        comboBox.setItems(filter, getKnownLoxoneComponentNames());
        comboBox.setSizeFull();
        return comboBox;
    }

    private Collection<String> getKnownLoxoneComponentNames() {
        return loxoneService.getKnownLoxoneComponentNames();
    }

    private abstract class SamplePayloadSelector extends HorizontalLayout {

        private final ArrayList<String> payloads;
        private final TextField translatedValueTextField;
        private TextField payloadLabel;
        int cursor = 0;

        public SamplePayloadSelector(Collection<String> payloads) {
            this.payloads = new ArrayList<>(payloads);
            payloadLabel = new TextField();
            payloadLabel.setReadOnly(true);
            Button prevButton = new Button("<", this::prev);
            Button nextButton = new Button(">", this::next);
            translatedValueTextField = new TextField();
            translatedValueTextField.setReadOnly(true);
            add(payloadLabel, prevButton, nextButton, translatedValueTextField);
        }

        abstract public String translateValue(String payload);

        private void next(ClickEvent<Button> buttonClickEvent) {
            cursor++;
            update();
        }

        private void prev(ClickEvent<Button> buttonClickEvent) {
            cursor--;
            update();
        }

        private void update() {
            if (payloads.isEmpty())
                return;
            fixCursor();
            String payload = payloads.get(cursor);
            payloadLabel.setValue(payload);
            try {
                translatedValueTextField.setValue(translateValue(payload));
            } catch (Exception e) {
                translatedValueTextField.setValue("ERROR");
                Notification.show(e.toString());
            }
        }

        private void fixCursor() {
            if (cursor > payloads.size() - 1) {
                cursor = payloads.size() - 1;
            }
            if (cursor < 0) {
                cursor = 0;
            }
        }

    }
}
