package io.github.dailystruggle.commandsapi.common.localCommands;

import io.github.dailystruggle.commandsapi.common.CommandExecutor;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * a command layer that can call a number of sub-commands, hence creating a tree structure.
 * This enables a typical command chain, where one command has a number of sub-commands
 */
public interface TreeCommand extends CommandsAPICommand {
    default void addParameter(String name, CommandParameter parameter) {
        getParameterLookup().put(name.toLowerCase(),parameter);
    }

    default void addSubCommand(CommandsAPICommand command) {
        getCommandLookup().put(command.name().toUpperCase(),command);
    }

    Map<String, CommandParameter> getParameterLookup();
    Map<String, CommandsAPICommand> getCommandLookup();

    @NotNull
    default List<String> onTabComplete(@NotNull UUID callerId,
                                       @NotNull Predicate<String> permissionCheckMethod,
                                       @NotNull String[] args) {
        return onTabComplete(callerId,permissionCheckMethod,args,0,null);
    }

    @NotNull
    default List<String> onTabComplete(@NotNull UUID callerId,
                                       @NotNull Predicate<String> permissionCheckMethod,
                                       @NotNull String[] args,
                                       int i,
                                       @Nullable Map<String,CommandParameter> tempParameters) {
        if(tempParameters == null) tempParameters = new HashMap<>();
        Set<String> parameterValues = new HashSet<>();
        List<String> possibleResults = new ArrayList<>();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();

        while (i<args.length && args[i].contains(String.valueOf(CommandsAPI.parameterDelimiter))) {
            if(i<args.length-1) {
                String[] arr = args[i].split(String.valueOf(CommandsAPI.parameterDelimiter));
                parameterValues.add(arr[0]);

                //check for sub-parameters
                CommandParameter parameter = parameterLookup.get(arr[0]);
                if(parameter == null) parameter = tempParameters.get(arr[0]);
                if(parameter != null) {
                    if (arr.length > 1) {
                        String[] subParameters = arr[1].split(String.valueOf(CommandsAPI.multiParameterDelimiter));
                        for (String subParamName : subParameters) {
                            Map<String, CommandParameter> parameterMap = parameter.subParams(subParamName);
                            if (parameterMap == null) continue;
                            tempParameters.putAll(parameterMap);
                        }
                    }
                }
            }
            ++i;
        }

        if(i==args.length) {//last value condition
            if(i>0) { //last value in a chain
                i--;
                int delimiterIdx = args[i].indexOf(CommandsAPI.parameterDelimiter);
                String arg = delimiterIdx > 0 ? args[i].substring(0, delimiterIdx) : args[i];

                if (delimiterIdx < 0) {
                    for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                        String key = entry.getKey();
                        CommandParameter value = entry.getValue();
                        if(key == null || value == null) continue;
                        String permission = value.permission();
                        if(parameterValues.contains(key)) continue;
                        if(permission==null || permissionCheckMethod.test(permission))
                            possibleResults.add(key + CommandsAPI.parameterDelimiter);
                    }
                    for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                        String key = entry.getKey();
                        CommandParameter value = entry.getValue();
                        if(key == null || value == null) continue;
                        String permission = value.permission();
                        if(parameterValues.contains(key)) continue;
                        if(permission==null || permissionCheckMethod.test(permission))
                            possibleResults.add(key + CommandsAPI.parameterDelimiter);
                    }
                    for (CommandsAPICommand command : getCommandLookup().values()) {
                        if (permissionCheckMethod.test(command.permission())) possibleResults.add(command.name());
                    }
                } else if (parameterLookup.containsKey(arg)) {
                    if(parameterValues.contains(arg)) return new ArrayList<>();
                    String val = args[i].substring(delimiterIdx+1);
                    Set<String> vals = Arrays.stream(val.split(String.valueOf(CommandsAPI.multiParameterDelimiter))).collect(Collectors.toSet());
                    CommandParameter parameter = parameterLookup.get(arg);
                    List<String> relevantValues = new ArrayList<>(parameter.relevantValues(callerId));
                    String front = (args[i].contains(String.valueOf(CommandsAPI.multiParameterDelimiter)))
                            ?  args[i].substring(0,args[i].lastIndexOf(CommandsAPI.multiParameterDelimiter))+CommandsAPI.multiParameterDelimiter
                            : arg+CommandsAPI.parameterDelimiter;
                    relevantValues = relevantValues.stream().filter(s -> !vals.contains(s)).map(s -> front + s).collect(Collectors.toList());
                    possibleResults.addAll(relevantValues);
                    for(String v : vals) {
                        Map<String, CommandParameter> subParams = parameter.subParams(v);
                        if (subParams != null) tempParameters.putAll(subParams);
                    }
                } else if (tempParameters.containsKey(arg)) {
                    if(parameterValues.contains(arg)) return new ArrayList<>();
                    String val = args[i].substring(delimiterIdx+1);
                    Set<String> vals = Arrays.stream(val.split(String.valueOf(CommandsAPI.multiParameterDelimiter))).collect(Collectors.toSet());
                    CommandParameter parameter = tempParameters.get(arg);
                    List<String> relevantValues = new ArrayList<>(parameter.relevantValues(callerId));
                    String front = (args[i].contains(String.valueOf(CommandsAPI.multiParameterDelimiter)))
                            ?  args[i].substring(0,args[i].lastIndexOf(CommandsAPI.multiParameterDelimiter))+CommandsAPI.multiParameterDelimiter
                            : arg+CommandsAPI.parameterDelimiter;
                    relevantValues = relevantValues.stream().filter(s -> !vals.contains(s)).map(s -> front + s).collect(Collectors.toList());
                    possibleResults.addAll(relevantValues);
                    for(String v : vals) {
                        Map<String, CommandParameter> subParams = parameter.subParams(v);
                        if (subParams != null) tempParameters.putAll(subParams);
                    }
                }
            }
            else { //only value
                for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (CommandsAPICommand command : getCommandLookup().values()) {
                    String permission = command.permission();
                    if (permission == null || permissionCheckMethod.test(permission)) possibleResults.add(command.name());
                }
            }
        }
        else { //not last value
            CommandsAPICommand nextCommand = getCommandLookup().get(args[i].toUpperCase());
            if(nextCommand != null) {
                return nextCommand.onTabComplete(callerId,permissionCheckMethod, args,i+1, tempParameters);
            }
            else {
                for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(parameterValues.contains(key)) continue; //already used prior
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(parameterValues.contains(key)) continue; //already used prior
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (CommandsAPICommand command : getCommandLookup().values()) {
                    String permission = command.permission();
                    if (permission==null || permissionCheckMethod.test(permission)) possibleResults.add(command.name());
                }
            }
        }

        possibleResults.add("help");
        if(args.length > 0) return possibleResults.stream().filter(s -> s.startsWith(args[args.length-1])).collect(Collectors.toList());
        else return possibleResults;
    }

    default CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                              @NotNull Predicate<String> permissionCheckMethod,
                              @NotNull Consumer<String> messageMethod,
                              @NotNull String[] args) {
        return onCommand(callerId,permissionCheckMethod,messageMethod,args,0,null);
    }

    default CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                                                 @NotNull Predicate<String> permissionCheckMethod,
                                                 @NotNull Consumer<String> messageMethod,
                                                 @NotNull String[] args,
                                                 int i,
                                                 @Nullable Map<String,CommandParameter> tempParameters) {
        if(tempParameters == null) tempParameters = new HashMap<>();
        if(!permissionCheckMethod.test(permission())) return CompletableFuture.completedFuture(false);
        Map<String,List<String>> parameterValues = new HashMap<>();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();
        for (; i < args.length; i++) {
            String arg = args[i];

            //catch delimiter with no value
            if (arg.endsWith(String.valueOf(CommandsAPI.parameterDelimiter))) {
                msgBadParameter(callerId,arg,"");
                return CompletableFuture.completedFuture(false);
            }

            String[] argSplit = arg.split(String.valueOf(CommandsAPI.parameterDelimiter));
            if (argSplit.length < 2) {//if it's a sub-command, process the current command and run the subcommand
                if(arg.equalsIgnoreCase("help") && !getCommandLookup().containsKey("HELP")) {
                    help(callerId,permissionCheckMethod).forEach(messageMethod);
                    return CompletableFuture.completedFuture(false);
                }

                CommandsAPICommand subCommand = getCommandLookup().get(arg.toUpperCase());

                //catch bad command
                if (subCommand == null) {
                    List<Map.Entry<String,CommandParameter>> parameters = new ArrayList<>(parameterLookup.size());
                    String lowerCase = arg.toLowerCase();
                    for(Map.Entry<String,CommandParameter> parameterEntry : parameterLookup.entrySet()) {
                        if(!permissionCheckMethod.test(parameterEntry.getValue().permission())) continue;
                        if(!parameterEntry.getValue().isRelevant.apply(callerId, lowerCase)) continue;
                        parameters.add(parameterEntry);
                    }

                    for(Map.Entry<String,CommandParameter> parameterEntry : tempParameters.entrySet()) {
                        if(!permissionCheckMethod.test(parameterEntry.getValue().permission())) continue;
                        if(!parameterEntry.getValue().isRelevant.apply(callerId, lowerCase)) continue;
                        parameters.add(parameterEntry);
                    }

                    if(parameters.isEmpty()) {
                        messageMethod.accept("invalid command - " + arg);
                        return CompletableFuture.completedFuture(false);
                    }

                    parameters.sort(Comparator.comparingInt(o -> o.getValue().priority));
                    Map.Entry<String, CommandParameter> parameterEntry = parameters.get(0);
                    List<String> list = parameterValues.get(parameterEntry.getKey());
                    if(list == null) list = new ArrayList<>();
                    list.add(lowerCase);
                    parameterValues.put(parameterEntry.getKey().toLowerCase(),list);

                    CommandParameter currentParameter = parameterEntry.getValue();
                    Map<String, CommandParameter> subParameterMap = currentParameter.subParams(lowerCase);
                    if(subParameterMap == null || subParameterMap.isEmpty()) continue;
                    tempParameters.putAll(subParameterMap);
                    continue;
                }

                //on top of trying subcommand, run current command in case of independent functionality

                CompletableFuture<Boolean> cont = new CompletableFuture<>();
                CommandExecutor commandExecutor = new CommandExecutor(this, callerId, parameterValues, subCommand, cont);
                CommandsAPI.commandPipeline.add(commandExecutor);

                //catch no perms for subcommand
                if (!permissionCheckMethod.test(subCommand.permission())) {
                    return CompletableFuture.completedFuture(false);
                }

                //run subcommand after this command is done
                int finalI = i+1;
                Map<String, CommandParameter> finalTempParameters = tempParameters;
                CompletableFuture<Boolean> res = new CompletableFuture<>();
                cont.whenCompleteAsync((aBoolean, throwable) -> {
                    if(aBoolean) {
                        CompletableFuture<Boolean> booleanCompletableFuture = subCommand.onCommand(callerId, permissionCheckMethod, messageMethod, args, finalI, finalTempParameters);
                        booleanCompletableFuture.whenComplete((aBoolean1, throwable1) -> res.complete(aBoolean1));
                    }
                });
                return res;
            }

            //otherwise, test and add the current arg to the list of parameters
            String paramName = argSplit[0].toLowerCase();
            CommandParameter currentParameter = parameterLookup.containsKey(paramName)
                    ? parameterLookup.get(paramName)
                    : tempParameters.get(paramName);
            if (currentParameter == null || !permissionCheckMethod.test(currentParameter.permission())) {
                msgBadParameter(callerId,paramName,"");
                return CompletableFuture.completedFuture(false);
            }

            String val = argSplit[1];

            //split args by specific delimiter
            //filter according to parameter limiter to guard possible answers
            //collect into list for command experience
            List<String> vals =
                    Arrays.stream(val.split(String.valueOf(CommandsAPI.multiParameterDelimiter)))
                            .filter(s -> {
                                Boolean pass = currentParameter.isRelevant.apply(callerId,s);
                                if(!pass) msgBadParameter(callerId,paramName,s);
                                return pass;
                            })
                            .collect(Collectors.toList());

            //only add if there are valid values
            if(!vals.isEmpty())
                parameterValues.putIfAbsent(paramName, vals);
            for(String s : vals) {
                Map<String, CommandParameter> subParameterMap = currentParameter.subParams(s);
                if(subParameterMap == null || subParameterMap.isEmpty()) continue;
                tempParameters.putAll(subParameterMap);
                for(int j = i+1; j < args.length; j++) {
                    String arg2 = args[j];
                    String[] argSplit2 = arg2.split(String.valueOf(CommandsAPI.parameterDelimiter));
                    if (argSplit2.length < 2) {
                        break;
                    }
                    String paramName2 = argSplit2[0].toLowerCase();
                    CommandParameter currentParameter2 = subParameterMap.get(paramName2);
                    if(currentParameter2 == null) continue;
                    if(!permissionCheckMethod.test(currentParameter2.permission())) continue;

                    String val2 = argSplit2[1];
                    List<String> vals2 =
                            Arrays.stream(val2.split(String.valueOf(CommandsAPI.multiParameterDelimiter)))
                                    .filter(s2 -> {
                                        boolean pass = currentParameter2.isRelevant.apply(callerId,s2);
                                        if(!pass) msgBadParameter(callerId,paramName,s2);
                                        return pass;
                                    })
                                    .collect(Collectors.toList());
                    if(!vals2.isEmpty()) {
                        tempParameters.putIfAbsent(paramName2,currentParameter2);
                        parameterValues.putIfAbsent(paramName2,vals2);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(onCommand(callerId, parameterValues, null));
    }

    default List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
        List<String> parents = new ArrayList<>();
        CommandsAPICommand parent = parent();
        while (parent!=null) {
            parents.add(parent.name());
            parent = parent.parent();
        }

        StringBuilder completeCommand = new StringBuilder();
        for(int i = parents.size()-1; i > 0; i--) {
            completeCommand.append(parents.get(i)).append(" ");
        }
        completeCommand.append(name()).append(" ");

        Map<String, CommandsAPICommand> commandLookup = getCommandLookup();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();
        List<String> possibleResults = new ArrayList<>(2+ parameterLookup.size() + commandLookup.size());
        possibleResults.add("Command: " + completeCommand +
                "\n    " + description());

        if(commandLookup.size()>0) {
            possibleResults.add("Subcommands: ");
            for (CommandsAPICommand command : commandLookup.values()) {
                if (!permissionCheckMethod.test(command.permission())) continue;
                possibleResults.add("  - /" + completeCommand + command.name() +
                        "\n    " + command.description());
            }
        }

        if(parameterLookup.size()>0) {
            possibleResults.add("Parameters: ");
            for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                if (!permissionCheckMethod.test(entry.getValue().permission())) continue;
                possibleResults.add("  - " + entry.getKey() + CommandsAPI.parameterDelimiter +
                        "\n    " + entry.getValue().description());
            }
        }
        return possibleResults;
    }
}
